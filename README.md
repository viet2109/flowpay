# FlowPay Backend

FlowPay is a Java 21 / Spring Boot 4.1 modular-monolith payment platform built
to demonstrate transactional consistency, financial concurrency, Idempotency,
double-entry accounting, and reliable event delivery. It uses controllable
Payment/Refund provider simulators; it does not process real money or store card
data.

Phases 0–7 are complete; Phase 7 Webhooks is DONE/FROZEN as of 2026-10-04 after
its clean quality gate. The functional simulator MVP is complete. Phase 8
production engineering has completed P8-T01's baseline/design and P8-T02's
production configuration, management isolation and probes. Remaining hardening
and staging tasks are pending.

## Implemented flow

```text
merchant Payment/Refund API
  → short business transaction + Outbox
  → confirmed RabbitMQ publication
      ├── success-only Ledger consumer → balanced, duplicate-safe postings
      └── six-event Webhook consumer → immutable event/subscription snapshot
          → claim + OPEN attempt transaction
          → HMAC-signed HTTP outside a database transaction
          → fenced result transaction → DELIVERED / RETRYING / DEAD
```

Dashboard JWTs protect merchant configuration and Webhook history; merchant API
keys protect Payment/Refund APIs. Public identifiers, tenant-scoped reads, RFC
9457 errors, optimistic locking, database constraints, and Flyway migrations are
part of the implementation. Source mutations use `Idempotency-Key` where
specified by the [API contract](docs/api-contract.md).

Webhook endpoints use `wep_`, events `evt_`, and deliveries `wdl_` public IDs.
Endpoint create/rotate returns a signing secret once; stored secrets are
AES-256-GCM ciphertext and normal reads never expose them. The consumer handles
`payment.processing`, `payment.succeeded`, `payment.failed`, `refund.processing`,
`refund.succeeded`, and `refund.failed`; UNKNOWN provider outcomes remain
non-terminal.

HTTP delivery is at-least-once: merchants deduplicate by `FlowPay-Event-Id`,
verify HMAC-SHA256 against the exact received body bytes, and tolerate events
arriving out of order. Retries reuse the same event ID/body. Five base delays
(10s, 30s, 2m, 10m, 1h) with deterministic additive 0–20% jitter give six normal
attempts. Expired leases recover safely but can cause external duplicates.
Dashboard manual retry schedules a DEAD delivery asynchronously and preserves
attempt count/history. Materializer RabbitMQ DLQ and delivery DEAD are separate
failure domains; neither changes financial records or published Outbox state.

## Local development

Prerequisites: JDK 21, Docker with Compose v2, and Maven (the wrapper selects
3.9.16). Ensure `JAVA_HOME` points to JDK 21.

1. Supply `WEBHOOK_SECRET_ENCRYPTION_KEY` to the application process through
   local secret tooling or IDE environment settings. It must be standard Base64
   encoding of exactly 32 cryptographically random bytes. Keep this key stable
   across restarts while encrypted endpoint records exist; replacing it is not
   a supported key-rotation workflow. Never commit environment files or keys.
2. Start PostgreSQL 16.15 and RabbitMQ 4.3:

   ```sh
   docker compose up -d --wait
   ```

3. Start the application with the development profile:

   ```sh
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   On Windows, use `mvnw.cmd`, or an installed Maven 3.9.x `mvn.cmd` if wrapper
   bootstrap is unavailable. The development profile uses an ephemeral JWT key
   pair, so dashboard sessions do not survive a restart.

Compose may read a root `.env` for its own variable substitution. Spring Boot
does **not** automatically load that file: export application variables or set
them in the IDE. If Compose database/user/password or ports differ from its
development defaults, also supply the matching `DB_URL`, `DB_USERNAME`,
`DB_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, and
`RABBITMQ_PASSWORD` to the application. Production additionally requires the
configured JWT public/private key locations; no production secrets have
defaults in the production profile.

Development health is available at `/actuator/health`. OTLP export is optional
for local development; without an OTLP collector, it can be disabled with
`MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=false`.

Production Webhook URL policy requires HTTPS and rejects obvious private,
localhost, loopback, metadata, and non-routable literal targets. Redirects are
never followed; connect/request timeouts default to 2s/5s. Only controlled local
receiver testing should opt into `WEBHOOK_ALLOW_INSECURE_LOCALHOST=true`; this
does not allow arbitrary HTTP/private-network destinations. Application URL
checks do not replace DNS-rebinding or network-egress controls.

## Production configuration and probes

Activate only `prod`, not `prod,dev` or `prod,test`. Startup rejects missing or
invalid mandatory DB/broker settings, ephemeral JWT keys, insecure-localhost
Webhooks, public Prometheus and non-Secure refresh cookies. Supply external
matching RSA PEM keys (X.509 public / PKCS#8 private, at least 2048 bits) and the
stable Webhook master key. Keep database credentials separate from the single-host
PostgreSQL JDBC URL; prod uses Rabbit `host`/`port`, not `addresses` overrides.
Configuration failures are sanitized without echoing secret values/parser causes.

Prod management defaults to `127.0.0.1:9091`. GET/HEAD
`/actuator/prometheus`, `/actuator/info` and minimal `/actuator/health/**` are
available only on that dedicated listener. All business-port `/actuator/**`
requests are denied, including authenticated scrapes. The business listener
instead serves `/livez` and `/readyz`: liveness checks process state; readiness
checks accepting-traffic state and DB SQL connectivity, not RabbitMQ/OTLP.
DB outage returns readiness 503; broker outage makes aggregate internal health
503 without blocking otherwise durable source + Outbox acceptance.

`MANAGEMENT_SERVER_PORT` must differ from the business port. Override
`MANAGEMENT_SERVER_ADDRESS` only for an explicitly private interface/container
network; never publish or proxy the management port to public ingress. There
is no management credential flow: loopback/private network is its trust boundary.
Actual Linux staging network enforcement is still P8-T13 work.

Graceful HTTP shutdown is enabled with a prod limit of 30s **per lifecycle phase**,
not a whole-process deadline. Container signal/grace periods, worker shutdown
and CPU/memory/JVM limits remain packaging/staging tasks. See the
[configuration, listener and lifecycle contract](docs/production-engineering.md#6-production-configuration-and-management-t02).

## Verification

```sh
./mvnw clean verify
```

Run with Docker available. PostgreSQL and RabbitMQ integration suites use fresh
Testcontainers resources, not the development database or H2. The gate includes
V001–V011 migration/upgrade checks, domain/application/API/security tests,
concurrency/crash/lease recovery, real DB + RabbitMQ + signed HTTP E2E, and
ArchUnit/transaction-boundary regressions. Do not accept skipped infrastructure
tests as a completed phase gate.

The P7-T16 gate passed on Java 21 / Maven 3.9.16: 59 focused Webhook migration
tests and 1,173 full-suite tests, with zero failures, errors, or skipped tests.
See the [architecture verification record](docs/architecture.md#phase-7-quality-gate)
for scope and infrastructure isolation.

P8-T02 passed focused configuration/security regressions and full `clean verify`:
1,227 tests in 173 suites, zero failures/errors/skips. The new prod-profile tests
use real separate listeners, disposable DB/broker resources and synthetic
temporary keys; deployment-network and image lifecycle proof remain later gates.

For a clean infrastructure smoke check, use a dedicated disposable Compose
project with separate container names, volumes, and ports. The checked-in
Compose file pins development container names, so `-p` alone is not isolation.
Never run `docker compose down -v` against a development/shared project without
explicit approval: it irreversibly deletes that project's persistent data.

## Documentation and scope

- [Product requirements](docs/product-requirements.md)
- [Business rules](docs/business-rules.md)
- [Domain model](docs/domain-model.md)
- [Architecture and verification](docs/architecture.md)
- [Database design](docs/database-design.md)
- [API contract](docs/api-contract.md)
- [Task roadmap](docs/task-roadmap.md)
- [Production engineering design and decision gates](docs/production-engineering.md)

Phase documents and `docs/README.md` are local-only planning/reference files,
not published documentation. The root README and linked tracked documents are
the repository's shared entry points.

Phase 8 targets reproducible Linux/container staging; no provider, cloud account
or external deployment has been selected. P8-T01 verifies the merged Phase 7
baseline; P8-T02 implements production startup and management/probe controls.
Stronger egress/DNS-rebinding controls, operational metrics/tracing, load testing,
security/dependency review,
packaging/staging, backup/recovery, and runbooks are still pending implementation.
The functional simulator MVP is not a claim of
production readiness, real payment processing, guaranteed cross-event ordering,
or exactly-once external HTTP delivery. Endpoint re-enable, dual-secret grace,
custom headers/templates/retry schedules, and automatic DLQ replay are not
implemented.
