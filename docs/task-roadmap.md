# FlowPay — Implementation Roadmap

## Working rule

A Phase is a roadmap unit.

A Task is the unit assigned to a coding agent.

Do not normally ask an agent to implement an entire business phase in one prompt. Complete, test, review, and commit tasks sequentially.

## Phase 0 — Foundation

Status: completed.

### P0-T01 — Bootstrap project

- Java 21.
- Spring Boot 4.1.0.
- Maven.
- `com.flowpay` base package.
- dependency foundation.
- Maven Wrapper.

### P0-T02 — Application configuration

- common/dev/prod/test profiles.
- environment-driven secrets.
- JPA `ddl-auto=validate`.

### P0-T03 — Docker infrastructure

- PostgreSQL.
- RabbitMQ.
- health checks.
- `.env.example`.

### P0-T04 — Flyway foundation

- Flyway enabled.
- foundation migration.
- migration naming rules.

### P0-T05 — Module/package skeleton

- common.
- infrastructure.
- identity.
- merchant.
- payment.
- refund.
- ledger.
- webhook.
- dependency boundaries.

### P0-T06 — Common API/error foundation

- ApiResponse.
- PageMeta.
- Problem Details.
- ErrorCode.
- validation errors.
- global exception handler.

### P0-T07 — Request correlation/logging

- `X-Request-Id`.
- MDC.
- secret-safe logging.

### P0-T08 — Observability foundation

- Actuator.
- health/readiness/liveness.
- Prometheus.
- OpenTelemetry foundation.

### P0-T09 — Testing and architecture enforcement

- PostgreSQL Testcontainers.
- RabbitMQ Testcontainers foundation.
- ArchUnit.
- no H2.

### P0-T10 — CI/quality gate

- Maven Wrapper.
- GitHub Actions.
- `clean verify`.
- README/local development.

## Phase 1 — Identity & Merchant

Status: completed and frozen.

Detailed task specification:

`phase-1-identity-merchant.md`

Task sequence:

```text
P1-T01 Database migration
   |
   +--> P1-T02 Identity domain/persistence
   |
   +--> P1-T03 Merchant domain/persistence
             |
             v
        P1-T04 Registration/onboarding
             |
        P1-T05 Password authentication/login
             |
        P1-T06 JWT access token
             |
        P1-T07 Refresh-token lifecycle
             |
        P1-T08 Dashboard security/principal
             |
        P1-T09 Merchant profile

P1-T10 API-key domain/persistence
   |
P1-T11 API-key management
   |
P1-T12 API-key authentication

P1-T01..T12
      |
      v
P1-T13 Integration/security tests
      |
      v
P1-T14 Quality gate/docs
```

Do not start Phase 2 until P1-T13 and P1-T14 pass.

## Phase 2 — Payment Core

Status: `DONE/FROZEN`.

Completed tasks:

- P2-T01 Phase 1 compatibility and Merchant contract.
- P2-T02 Payment database migration.
- P2-T03 Money and Payment shared types.
- P2-T04 PaymentIntent aggregate.
- P2-T05 PaymentTransaction aggregate.
- P2-T06 Payment persistence adapters.
- P2-T07 Merchant ownership integration.
- P2-T08 Create PaymentIntent use case.
- P2-T09 Payment query and read APIs.
- P2-T10 PaymentProviderPort and simulator.
- P2-T11 Prepare confirmation transaction (TX1).
- P2-T12 Finalize confirmation transaction (TX2).
- P2-T13 Confirm Payment orchestration with the provider call outside a database transaction.
- P2-T14 Integration, security, and concurrency tests.
- P2-T15 Quality gate and documentation.

See `phase-2-payment-core.md` for dependencies, acceptance criteria, and the
required implementation order.

## Phase 3 — Idempotency

Status: `DONE/FROZEN`.

Completed tasks:

- P3-T01 Phase 2 exit gate and Idempotency architecture freeze.
- P3-T02 Idempotency database migration.
- P3-T03 Idempotency domain and persistence.
- P3-T04 Operation scope, key validation, and request fingerprinting.
- P3-T05 Concurrent Idempotency acquisition engine.
- P3-T06 Atomic idempotent create-payment flow.
- P3-T07 Public create-payment API and replay.
- P3-T08 Confirm Idempotency reservation.
- P3-T09 Confirm completion and response snapshot.
- P3-T10 Public confirm-payment API.
- P3-T11 Expiration and cleanup.
- P3-T12 Architecture, security, and error-mapping tests.
- P3-T13 End-to-end concurrency and replay tests.
- P3-T14 Quality gate and documentation freeze.

See `phase-3-idempotency.md` for frozen decisions, dependencies, acceptance
criteria, and the mandatory implementation order.

## Phase 4 — Refund & Concurrency

Status: `DONE/FROZEN`.

Completed tasks:

- P4-T01 Phase 3 exit gate and Refund architecture freeze.
- P4-T02 Refund database migration.
- P4-T03 Refund aggregate and domain types.
- P4-T04 Refund persistence adapters.
- P4-T05 Payment refund-capacity public API.
- P4-T06 Refund provider port and simulator.
- P4-T07 Refund Idempotency operation and fingerprint.
- P4-T08 Prepare Refund transaction.
- P4-T09 Finalize Refund transaction.
- P4-T10 Idempotent Refund orchestration.
- P4-T11 Public create-Refund API.
- P4-T12 Refund query APIs.
- P4-T13 Idempotency and concurrency integration tests.
- P4-T14 Architecture, security, and regression tests.
- P4-T15 Quality gate and documentation freeze.

See `phase-4-refund-concurrency.md` for frozen decisions, dependencies,
acceptance criteria, and the mandatory implementation order.

## Phase 5 — Ledger

Status: `DONE/FROZEN`.

Completed tasks:

- P5-T01 Phase 4 exit gate and Ledger architecture freeze.
- P5-T02 Ledger database migration.
- P5-T03 LedgerAccount domain and accounting identity.
- P5-T04 LedgerTransaction aggregate and balance invariant.
- P5-T05 Ledger persistence adapters.
- P5-T06 Concurrency-safe Ledger account provisioning.
- P5-T07 Idempotent Ledger posting engine.
- P5-T08 Payment success Ledger integration.
- P5-T09 Refund success Ledger integration.
- P5-T10 Reversal posting.
- P5-T11 Ledger atomicity and accounting integration tests.
- P5-T12 Duplicate posting and concurrency tests.
- P5-T13 Architecture, security, and regression tests.
- P5-T14 Quality gate and documentation freeze.

See `phase-5-ledger.md` for frozen decisions, dependencies, acceptance criteria,
and the mandatory implementation order.

## Phase 6 — Outbox & RabbitMQ

Status: `DONE/FROZEN`.

Completed tasks:

- P6-T01 Phase 5 exit gate and eventing architecture freeze.
- P6-T02 Outbox database migration.
- P6-T03 Versioned integration-event contracts and producer ports.
- P6-T04 Transactional Outbox writer and persistence.
- P6-T05 RabbitMQ topology and confirmed publisher.
- P6-T06 Outbox relay and publication retry.
- P6-T07 Ledger integration-event consumer.
- P6-T08 Payment success Outbox cutover.
- P6-T09 Refund success Outbox cutover.
- P6-T10 Consumer retry and dead-letter handling.
- P6-T11 Relay and consumer failure/concurrency hardening.
- P6-T12 End-to-end PostgreSQL and RabbitMQ verification.
- P6-T13 Architecture, data-safety, and regression tests.
- P6-T14 Quality gate and documentation freeze.

See `phase-6-outbox-rabbitmq.md` for frozen decisions, dependencies,
acceptance criteria, and the mandatory implementation order.

## Phase 7 — Webhooks

Status: `DONE/FROZEN` as of 2026-10-04.

Completed tasks:

- P7-T01 Phase 6 exit gate and Webhook architecture freeze.
- P7-T02 Webhook configuration migration.
- P7-T03 WebhookEndpoint domain, URL policy, and secret cryptography.
- P7-T04 Webhook endpoint persistence and dashboard APIs.
- P7-T05 Complete Webhook source event catalog.
- P7-T06 Webhook delivery migration.
- P7-T07 WebhookEvent, Delivery, and Attempt domain/persistence.
- P7-T08 RabbitMQ Webhook topology and event materialization.
- P7-T09 HMAC signing and outbound HTTP adapter.
- P7-T10 Delivery claim, lease, and attempt execution.
- P7-T11 Retry, DEAD state, and stale-delivery recovery.
- P7-T12 Manual retry and delivery dashboard APIs.
- P7-T13 End-to-end event-to-Webhook verification.
- P7-T14 Concurrency, failure, and security hardening.
- P7-T15 Architecture and regression tests.
- P7-T16 Quality gate and documentation freeze.

P7-T16 passed 59 focused Webhook migration tests and the complete 1,173-test
`clean verify` gate on Java 21 / Maven 3.9.16, with zero failures, errors, or skips.
Clean PostgreSQL/RabbitMQ Compose health checks used a separate disposable
project; Testcontainers independently supplied fresh test infrastructure.
Existing development volumes were preserved. V001–V011, source-event contracts,
Webhook/financial regressions, and architecture boundaries remain verified.
The functional simulator MVP is complete; Phase 6 remains DONE/FROZEN.

See the local `phase-7-webhooks.md` plan for frozen decisions, dependencies,
acceptance criteria, and the mandatory implementation order.

## Phase 8 — Production Engineering

Status: `IN PROGRESS` as of 2026-10-11.

Completed: P8-T01 baseline/design, P8-T02 production configuration, health and
management security, P8-T03 Payment/Refund/Idempotency metrics, P8-T04
Outbox/Ledger/Webhook operational metrics, P8-T05 synchronous tracing and
P8-T06 durable Outbox trace context and P8-T07 consumer/delayed-worker tracing.
Next: P8-T08 (not started).

The user selected staging Linux/container with no provider/external deployment.
Phase 7 source `d348b10` was pushed and merged into remote main at `0b96400`;
the merge and initial phase branch match the tested tree. A fresh JDK21/Maven
3.9.16 `clean verify` passed 1,173 tests in 171 suites with zero failures/errors/
skips. Selected regressions ran inside that full gate, not a separate focused
command. Only documentation changes in P8-T01, so an unchanged full suite is
not repeated for the final documentation commit.

Work continues on `feature/phase-8-production-engineering`. Do not push the
phase branch before completion unless explicitly requested. Existing untracked
`.env` and `.github/` remain user-owned and excluded from this task.

| Task | Name | Depends on | Status |
|---|---|---|---|
| P8-T01 | Phase 7 Exit Gate and Production Engineering Freeze | Phase 7 DONE/FROZEN and merged | DONE |
| P8-T02 | Production Configuration, Health and Management Security | P8-T01 | DONE |
| P8-T03 | Payment, Refund and Idempotency Metrics | P8-T01, P8-T02 | DONE |
| P8-T04 | Outbox, Ledger and Webhook Operational Metrics | P8-T03 | DONE |
| P8-T05 | HTTP Tracing and Safe Log Correlation | P8-T02 | DONE |
| P8-T06 | Durable Outbox Trace Context | P8-T01, P8-T05 | DONE |
| P8-T07 | Consumer and Webhook Worker Tracing | P8-T06 | DONE |
| P8-T08 | Monitoring Stack, Dashboards and Alerts | P8-T02, P8-T03, P8-T04, P8-T05, P8-T06, P8-T07 | PLANNED |
| P8-T09 | Webhook SSRF, DNS-Rebinding and Egress Protection | P8-T01, P8-T02 | PLANNED |
| P8-T10 | API and Authentication Security Review | P8-T01, P8-T02 | PLANNED |
| P8-T11 | CI Verification and Supply-Chain Gates | P8-T01, P8-T10 | PLANNED |
| P8-T12 | Production Container Image | P8-T02, P8-T11 | PLANNED |
| P8-T13 | Isolated Linux Staging Deployment | P8-T08, P8-T09, P8-T10, P8-T11, P8-T12 | PLANNED |
| P8-T14 | Load, Stress and Soak Verification | P8-T13 | PLANNED |
| P8-T15 | Multi-Instance and Operational Failure Drills | P8-T13, P8-T14 | PLANNED |
| P8-T16 | Backup, Restore and Disaster-Recovery Drill | P8-T13, P8-T15 | PLANNED |
| P8-T17 | Operational Runbooks and Release Checklist | P8-T08, P8-T13, P8-T14, P8-T15, P8-T16 | PLANNED |
| P8-T18 | Production Engineering Quality Gate and Documentation Freeze | P8-T01 through P8-T17 | PLANNED |

See the tracked [production-engineering design record](production-engineering.md)
for frozen scope and implementation gates, and the local
`phase-8-production-engineering.md` plan for complete assignment requirements.
Runtime controls, concrete proxy/tool versions, technical trace migrations and
numeric performance/recovery acceptance are owned by their tasks; preparation
does not claim they are already implemented or measured. No financial/API/event
schema/locking change or external provisioning is introduced by P8-T01.

P8-T02 verifies fail-closed prod startup with safe diagnostics, persistent
matching RSA keys, dev/test separation, dedicated loopback-by-default management
scraping and business-port Actuator denial (including valid JWT/forwarded-header
attempts). Main `/livez` and `/readyz` separate process health from DB/availability
readiness; real broker outage leaves readiness UP, DB outage makes it DOWN.
An in-flight HTTP request drains during bounded graceful context shutdown.
No business API/event/schema/dependency/transaction/locking changes. Actual
staging networks, image signals, resource limits and worker lifecycle remain
their later gates; private management-network access is the trust boundary.

Verification on JDK21/Maven3.9.16: final eight-class focused regression command
passed 127 tests, then the expanded startup validator passed 45 tests (41 overlap,
131 distinct focused cases). Full `clean verify` passed 1,227 tests in 173 suites,
zero failures/errors/skips, 5m16s. Only optional OTLP metrics export was disabled;
required DB/broker tests ran. Compilation/ArchUnit/transaction checks and
`git diff --check` pass; no formatting plugin is configured. Management tests
use owned disposable PostgreSQL/RabbitMQ and temporary synthetic RSA keys, not
development resources. Local phase/index corrections stay unstaged; pre-existing
`.env`/`.github/` remain untouched. Task commit stays local on the phase branch.
The packaged executable JAR also rejects mixed prod/dev profiles before context
initialization (exit 1 with the expected sanitized guard message); no deployment
or database connection was used for that smoke check.

P8-T03 freezes six bounded metric families before instrumentation. Owning
application components observe Payment/Refund classifications after commit,
provider execution latency and Idempotency diagnostic decisions versus committed
NEW reservations. Rollback/equivalent replay do not inflate logical counters;
UNKNOWN preserves existing financial semantics and repeated Refund UNKNOWN is
deduplicated using persisted metadata under the existing row lock. Registry
failure and an actual OTLP HTTP 503 collector do not change business results.
The catalog, units, labels, SLO buckets and process-local/multi-instance limits
are recorded in `production-engineering.md`; no DB sampling is added here.

Verification on Java21/Maven3.9.16: 179 focused tests in 15 classes pass; full
`clean verify` passes 1,256 tests in 176 suites, zero failures/errors/skips,
5m29s. Coverage includes real PostgreSQL commit/rollback, provider success/known
failure/UNKNOWN/exception, replay/conflict/concurrency, Prometheus cardinality/
redaction, actual exporter outage, ArchUnit and transaction regressions. Default
optional OTLP export is disabled except the owned outage fixture's explicit
enablement. Required DB/broker tests ran. Compilation/static checks and
`git diff --check` pass. No dependency/schema/API/event/state/locking/transaction
change or external deployment. T04 was unstarted at the T03 checkpoint; task
commit stays local.
Phase/index/agent documents are not staged; `.env`/`.github/` remain untouched.

P8-T04 adds cached owning Outbox/Webhook read-only projections, shared-DB count/
age/freshness gauges, Outbox diagnostic confirmation attempts versus committed
publication, Ledger decisions/duplicates versus committed postings and Webhook
claim/HTTP/delivery/expired-lease observations. Counters retain after-commit and
fencing semantics; diagnostic execution is separate. Catalog/units/labels and
aggregation are frozen in production-engineering.md. Cadence defaults 30s,
validated 10s–5m, monotonic single-flight; one aggregate statement per owner,
2s statement timeout, no scrape-time DB access or ambient financial TX.
Failures retain stale snapshots/age, not fabricated zeros. Sampling uses its
separate scheduler without replacing business scheduling. Shared gauges must
not be summed across replicas; exact queries can scan retained history, with
actual staging cost owned by T14/T15.

Final focused Java21/Maven3.9.16 gate: 186 tests in 19 classes, zero failures/
errors/skips, 2m09s. Full clean verify: 1,287 tests in 180 suites, zero failures/
errors/skips, 5m31s. Coverage includes known projection fixtures, real DDL-lock
timeout, replica/cache/cadence/stale behavior, rollback after callback registration,
redelivery/concurrency/conflict, late HTTP lease fencing and unavailable recording.
Required DB/broker tests ran; optional default OTLP export disabled except the
owned T03 HTTP 503 exporter fixture. Compilation/ArchUnit/transaction/static
checks pass. No dependency/schema/index/API/event/state/locking/transaction or
retry/lease budget change; no external deployment. Local phase/index corrections
stay unstaged, .env/.github preserved; task commit remains local. P8-T05 was
unstarted at the T04 checkpoint.

### T05 completion evidence (2026-10-04)

P8-T05 reuses Boot's installed SDK/bridge, HTTP observations and asynchronous
batch processor. Incoming propagation is traceparent-only; request/trace/span
IDs stay distinct. Scoped provider/Webhook CLIENT spans carry bounded outcomes
and preserve external-call transaction boundaries/results. Final exporter
allow-list removes URLs, credentials, bodies, arbitrary attributes/resource/
scope, events, links, tracestate and exception descriptions. Console MDC has
traceId/spanId; no internal headers enter signed merchant Webhook HTTP.
Export is opt-in, sampling/queue/timeouts are explicit, dev sampling defaults
off. Export outage can lose telemetry, not change a committed financial result.

Final focused tracing gate: 22 tests/five classes, 1m06s; earlier broader
regression gate: 249 tests/19 classes, 1m43s. The added provider-exception fixture
was corrected and rerun in the final focused gate. Full clean verify: 1,307 tests
in 184 suites, 5m53s, zero failures/errors/skips on JDK21/Maven3.9.16 with all
required PostgreSQL/RabbitMQ suites. Includes real HTTP sampled/unsampled/
malformed context, 24 requests/four pooled threads, safe export/log correlation,
provider ancestry/replay/exception, collector HTTP 503/throwing export and
missing collector, Webhook body/header preservation, security/TX regressions.
No dependency/schema/API/event/state/lock/TX/retry change or deployment.
Only documentation finalized after that gate; ignored phase/index corrections
stay local, .env/.github preserved. Commit stays local on the existing phase
branch. At the T05 checkpoint P8-T06 was next and unstarted; no end-to-end
async tracing, collector/staging/performance acceptance claimed.

### T06 completion evidence (2026-10-04)

Reviewed V012 adds `outbox_events.traceparent VARCHAR(55) NULL` only, no
backfill/default/index/business constraint; V001-V011 unchanged. Infrastructure
captures valid source identity/sample flags in the existing source TX, drops
absent/bad context fail-open and carries it through persisted snapshots/retries
as a Rabbit header only. Serialized V1 payload/envelope bytes, business dedupe,
confirm/backoff/TX behavior and public signed Webhook HTTP remain unchanged.
No ambient relay context, baggage/tracestate persistence, new dependency or
export policy/span expansion. T07 owns publication/consumer/worker tracing.

Final focused gates: 21 tests/three classes (46.7s) and 27 metrics/Ledger tests/
two classes (51.7s), zero failures/errors/skips. Earlier 128-case broad run and
first full run exposed test fixture/mock-signature/schema expectations, corrected
without weakening business assertions. Final clean verify: 1,331 tests/187
suites, zero failures/errors/skips, 6m45s, Java21.0.12/Maven3.9.16, packaged JAR.
All required owned PostgreSQL/RabbitMQ suites ran, including actual app restart,
source rollback, 16 operations/four reused threads, old/enriched duplicates and
concurrent Rabbit confirms/headers. Compilation/architecture/transaction/static
checks pass. No deployment, business state/locking or retry-budget change.
Tracked docs synchronized; local phase/index corrected and unstaged. `.env`/
`.github/` preserved. Commit stays local on the phase branch; no push/merge.
P8-T07 is next, not started; Phase 8 is not complete.

### P8-T07 completion checkpoint (2026-10-11)

Independent bounded publication, consumer, delivery/recovery spans use strict
source-context validation. Reviewed V013 first-materialization Webhook metadata
preserves causality across restart without changing public bodies/HMAC, dedupe,
ACK/retry/lease or financial transactions. Focused checkpoints verified 125
unique cases; the final restart/concurrent-worker rerun passed after correcting
an advisory SKIP LOCKED candidate-discovery fixture. Full clean verify passes
1,351 tests/191 suites with no failures/errors/skips (7m39s), packaged JAR.
Scope/SQL lookup limits and full evidence are recorded in production-engineering.md.
No deployed collector/staging claim or T08 work. Commit remains local; phase/index/
agent/env/user-owned .github paths are excluded. Next: P8-T08, not started.

## Agent task template

Every coding-agent task should contain:

1. Documents to read.
2. Exact task ID/scope.
3. Requirements.
4. Explicit non-goals.
5. Architecture constraints.
6. Tests required.
7. Definition of Done.
8. Final report requirements.
9. `Do not continue to the next task.`

A coding agent must not silently alter public APIs, schema, state machines, dependencies, locking strategy, or module boundaries when the task does not explicitly authorize that change.
