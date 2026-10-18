# FlowPay Backend

FlowPay is a Java 21 / Spring Boot 4.1 payment-platform backend built as a
modular monolith. It demonstrates financial concurrency control, idempotent
APIs, double-entry accounting and reliable asynchronous delivery.

**Simulator only:** payment and refund providers are controllable simulators.
FlowPay does not process real money or store card data. The Linux/container lab
has passed its quality gates; no public hosting or frontend is provisioned.

## Capabilities

- Identity and merchant management, dashboard JWTs and rotating refresh cookies.
- Merchant API keys, tenant isolation and prefixed public resource IDs.
- Payment intents and provider attempts, including ambiguous UNKNOWN outcomes.
- Partial/full refunds with database-protected refund-capacity reservations.
- Append-only, balanced Ledger postings with duplicate protection and reversals.
- Transactional Outbox, confirmed RabbitMQ publication and idempotent consumers.
- Signed Webhooks with immutable payloads, jittered retries, lease recovery,
  delivery history and explicit manual retry for DEAD deliveries.
- Private health/management endpoints, bounded metrics and privacy-safe tracing.

## Architecture

```text
Payment / Refund API
  -> short business transaction + Outbox
  -> confirmed RabbitMQ publication
      -> success-only Ledger consumer -> balanced, duplicate-safe postings
      -> Webhook consumer -> immutable event and subscription snapshot
          -> claim transaction -> signed HTTP without a DB transaction
          -> fenced result transaction -> DELIVERED / RETRYING / DEAD
```

Each business module separates API, application use cases, domain behavior and
infrastructure adapters. Cross-module access uses explicit public APIs or
integration events, not another module's repositories or JPA entities.
PostgreSQL constraints and locking protect financial invariants; Flyway owns
schema evolution. Money uses integer minor units, never floating point.

See [architecture](docs/architecture.md), [domain model](docs/domain-model.md)
and [business rules](docs/business-rules.md) for the detailed contracts.

## Run locally

Requirements: JDK 21, Linux Docker containers and Docker Compose v2 or later.
The Maven wrapper selects Maven 3.9.16. Set `JAVA_HOME` to JDK 21.

1. Supply `WEBHOOK_SECRET_ENCRYPTION_KEY` through your IDE environment or local
   secret tooling: standard Base64 encoding of exactly 32 cryptographically
   random bytes. Keep it stable across restarts while encrypted Webhook records
   exist. Never commit keys or environment files.
2. Start the development PostgreSQL and RabbitMQ services:

   ```sh
   docker compose -f docker-compose.yaml up -d --wait
   ```

3. Run the development profile:

   ```sh
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   In PowerShell:

   ```powershell
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
   ```

The API defaults to `http://localhost:8080/api/v1`; development health is at
`http://localhost:8080/actuator/health`. Development JWT keys are ephemeral, so
dashboard sessions do not survive application restart.

Compose may read a root `.env` for variable substitution; Spring Boot does not
automatically load that file. Export application variables separately. If you
override Compose defaults, supply matching `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
`RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD` to
the application. Development credentials and exposed infrastructure ports are
not a production deployment configuration.

OTLP tracing is opt-in and no collector is required for local API development.
Optional OTLP metric export can be disabled with
`MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=false`.

## API integration

The [API contract](docs/api-contract.md) documents requests, responses, errors,
authentication, pagination, idempotency and Webhook signing.

- Dashboard APIs use JWT Bearer authentication; merchant Payment/Refund APIs
  use merchant API keys. Never embed a long-lived merchant key in a public
  frontend bundle.
- Register/login/refresh/logout POSTs require exactly one `X-FlowPay-CSRF: 1`
  header, including non-browser clients. Cross-origin auth preflights are
  intentionally denied; a separately hosted frontend needs a reviewed
  same-origin proxy/BFF or explicit security-contract work, not wildcard CORS.
- Financial mutations require `Idempotency-Key` where specified. The same key
  with changed input returns a conflict rather than another operation.
- Webhook receivers verify HMAC-SHA256 over the exact received bytes and
  deduplicate by `FlowPay-Event-Id`. Delivery is at-least-once and events may
  arrive out of order; a timeout does not prove the remote side had no effect.

Production Webhook delivery requires HTTPS and enforcing network egress. URL
validation alone does not prevent DNS rebinding. See the
[egress guide](docs/webhook-egress.md) before connecting external receivers.

## Verification

With Docker available:

```sh
./mvnw clean verify
```

Use `.\mvnw.cmd clean verify` in PowerShell. Integration tests use disposable
PostgreSQL/RabbitMQ Testcontainers resources, not H2 or the development database.
The suite includes domain/API/security, migrations, concurrency, architecture,
transaction boundaries and signed-HTTP end-to-end coverage.

The stricter CI gate additionally requires fresh complete reports with no
infrastructure skips, workflow validation and independent security controls:

```sh
python3 -B -m unittest discover -s ops/ci/tests
python3 -B ops/ci/gate.py verify
python3 -B ops/ci/gate.py security
```

Use `python` instead of `python3` on Windows. Run gates sequentially on a
resource-constrained host. Source security, image safety, staging, performance
and restore evidence are separate checks; one passing suite does not replace
the others. See [CI and supply-chain gates](docs/ci-supply-chain.md).

## Documentation

| Reader goal | Reference |
|---|---|
| Understand product scope and behavior | [Requirements](docs/product-requirements.md), [business rules](docs/business-rules.md) |
| Review module boundaries and financial invariants | [Architecture](docs/architecture.md), [domain model](docs/domain-model.md), [database design](docs/database-design.md) |
| Integrate a client or Webhook receiver | [API contract](docs/api-contract.md), [auth/security review](docs/api-security-review.md) |
| Configure health, metrics and tracing | [Production engineering](docs/production-engineering.md), [monitoring runbook](docs/monitoring-runbook.md) |
| Build and verify the runtime | [Container runtime](docs/container-runtime.md), [staging guide](docs/staging-deployment.md), [CI gates](docs/ci-supply-chain.md) |
| Review measured load and recovery | [Load testing](docs/load-testing.md), [failure drills](docs/operational-drills.md), [backup/restore](docs/backup-restore.md) |
| Operate or assess a release | [Operations runbook](docs/operations-runbook.md), [release checklist](docs/release-checklist.md), [quality-gate evidence](docs/production-exit-audit.md) |

Security reviews retain the rationale for dependency overrides and exact,
expiring source-scanner false-positive reviews. These are reviewer/operator
evidence, not approval to accept real secrets or deploy with stale scans.

## Deployment limits

The accepted baseline is a disposable Linux/container simulator lab, not a
live payment service. A retained/public target still needs TLS/DNS, protected
secret storage, separate database migration/runtime privileges, independent
backups, notification routing and resource/security review. Follow the release
checklist for that specific target; CI does not publish images or deploy.

Single-host drills do not prove multi-host HA or zero downtime. Load targets
apply only to their recorded artifact, host and workload. There is no automatic
provider reconciliation, settlement, blind DLQ replay or repair of financial
history. Do not delete development/shared volumes to reproduce a clean lab.
