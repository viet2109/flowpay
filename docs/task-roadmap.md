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

Status: `IN PROGRESS` as of 2026-10-17. Gate implementation and security
acceptance are separate: P8-T11 is implemented, but its security gate is BLOCKED.

Completed: P8-T01 baseline/design, P8-T02 production configuration, health and
management security, P8-T03 Payment/Refund/Idempotency metrics, P8-T04
Outbox/Ledger/Webhook operational metrics, P8-T05 synchronous tracing and
P8-T06 durable Outbox trace context, P8-T07 consumer/delayed-worker tracing and
P8-T08 monitoring lab/dashboards/alerts and P8-T09 effective-destination/egress
protection, plus P8-T10 approved API/auth/ingress security controls.
The user approved Apache HttpClient 5 specifically
for Webhook after reproduced JDK Java 21/25 IPv6-literal SNI failures. Java 21,
strict TLS/IP-SAN, proxy/network enforcement, signing and all financial/API
contracts remain unchanged. Client 5.6.4/Core 5.4.4 selection and historical
rejected-spike evidence are recorded in [egress design and review](webhook-egress.md).
Final verification passes: 205 focused tests, full 1,388 tests/193 suites, all
34 network/transport/startup assertions and affected native monitoring smoke on
the same JAR. No failures/errors/skips at the T09 checkpoint.
P8-T10 DONE: reviewed auth/edge contracts approved by the user.
The implemented auth marker/closed-preflight and pinned NGINX TCP-peer limits
preserve bearer/financial semantics. Final 266-test focused and full 1,405-test/
195-suite gates pass, zero failures/errors/skips. Native edge policy, real-app
financial/replay/rate-limit, monitoring outage/recovery and all 34 Webhook egress
assertions pass on the final artifact. Historical
red cookie CSRF reproductions and current controls are recorded in
[API security review](api-security-review.md).
P8-T11 gate implementation is complete: approved workflow adoption, pinned
verification/scanners, real infrastructure coverage enforcement, safe SBOM and
redacted evidence. Focused gate tests and all 1,405 Java tests pass; security
acceptance does not. Ten baseline secret-format detections and 17 HIGH/CRITICAL
dependency findings remain blocking, without exceptions or forced upgrades.
P8-T12 now implements the verified-artifact runtime image and image acceptance
gates; runtime verification and image-security acceptance are recorded in
[container runtime](container-runtime.md). It is not release clearance or
permission to bypass the blocked gates. No push/merge; local-only corrections
remain unstaged. Phase 8 is not production/staging accepted. T13 isolated staging
implementation/runtime verification now passes; see
[staging deployment](staging-deployment.md). Security release remains BLOCKED.

The user selected staging Linux/container with no provider/external deployment.
Phase 7 source `d348b10` was pushed and merged into remote main at `0b96400`;
the merge and initial phase branch match the tested tree. A fresh JDK21/Maven
3.9.16 `clean verify` passed 1,173 tests in 171 suites with zero failures/errors/
skips. Selected regressions ran inside that full gate, not a separate focused
command. Only documentation changes in P8-T01, so an unchanged full suite is
not repeated for the final documentation commit.

Work continues on `feature/phase-8-production-engineering`. Do not push the
phase branch before completion unless explicitly requested. Existing untracked
`.env` remains user-owned and excluded. The sole `.github` workflow is adopted
only with the user's explicit P8-T11 approval.

| Task | Name | Depends on | Status |
|---|---|---|---|
| P8-T01 | Phase 7 Exit Gate and Production Engineering Freeze | Phase 7 DONE/FROZEN and merged | DONE |
| P8-T02 | Production Configuration, Health and Management Security | P8-T01 | DONE |
| P8-T03 | Payment, Refund and Idempotency Metrics | P8-T01, P8-T02 | DONE |
| P8-T04 | Outbox, Ledger and Webhook Operational Metrics | P8-T03 | DONE |
| P8-T05 | HTTP Tracing and Safe Log Correlation | P8-T02 | DONE |
| P8-T06 | Durable Outbox Trace Context | P8-T01, P8-T05 | DONE |
| P8-T07 | Consumer and Webhook Worker Tracing | P8-T06 | DONE |
| P8-T08 | Monitoring Stack, Dashboards and Alerts | P8-T02, P8-T03, P8-T04, P8-T05, P8-T06, P8-T07 | DONE |
| P8-T09 | Webhook SSRF, DNS-Rebinding and Egress Protection | P8-T01, P8-T02 | DONE |
| P8-T10 | API and Authentication Security Review | P8-T01, P8-T02 | DONE |
| P8-T11 | CI Verification and Supply-Chain Gates | P8-T01, P8-T10 | DONE (gates); security BLOCKED |
| P8-T12 | Production Container Image | P8-T02, P8-T11 | IMPLEMENTED/runtime verified; security BLOCKED (not fully accepted) |
| P8-T13 | Isolated Linux Staging Deployment | P8-T08, P8-T09, P8-T10, P8-T11, P8-T12 | IMPLEMENTED/runtime verified; security BLOCKED (not fully accepted) |
| P8-T14 | Load, Stress and Soak Verification | P8-T13 | DONE: independent steady/peak/stress/30-minute soak accepted on frozen host/artifact; security release remains BLOCKED |
| P8-T15 | Multi-Instance and Operational Failure Drills | P8-T13, P8-T14 | DONE: eight owned two-replica failure/recovery drills and financial/history audits pass; release security remains BLOCKED |
| P8-T16 | Backup, Restore and Disaster-Recovery Drill | P8-T13, P8-T15 | DONE: encrypted isolated cold/mismatch restores, key/history/invariant audits and fixed lab recovery targets pass; off-host schedule unprovisioned, release security BLOCKED |
| P8-T17 | Operational Runbooks and Release Checklist | P8-T08, P8-T13, P8-T14, P8-T15, P8-T16 | DONE: actionable approval-bound runbooks, NO-GO checklist and owned read-only command rehearsal; release security BLOCKED |
| P8-T18 | Production Engineering Quality Gate and Documentation Freeze | P8-T01 through P8-T17 | IN PROGRESS: security PASS with owner-approved bounded source reviews; final-artifact operational/clean-commit gates pending; not DONE/FROZEN |

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

### P8-T08 completion checkpoint (2026-10-11)

Independent digest-pinned Prometheus/Grafana/OTLP Collector/Tempo lab with
versioned two-datasource/21-panel provisioning and 12 operational alerts/runbooks.
External fixture secrets, private infrastructure ports, loopback/authenticated
Grafana, finite budgets/retention and fresh shared-DB max (not sum) are explicit.
Promtool validates 16 rules and 14 scenarios/28 firing-resolution/aggregation
assertions; Compose/static/runbook coverage and Collector config checks pass.
Final clean owned Linux-container acceptance passes real metrics, every dashboard
query, E2E stored trace/proxy/privacy, all-four-monitor outage/recovery, financial
replay/conflict and six balanced unique Ledger posts/twelve entries. Focused Java
regressions pass 15 tests/four classes, zero failures/errors/skips (1m56s).

No full application suite rerun: no Java/application-default configuration,
security wiring, schema, transaction, financial or messaging behavior changed.
The tested JAR remains the T07 full-gate artifact; that prior 1,351-test run is
provenance, not a new T08 gate. Environment/artifact/limitations are in
production-engineering.md and monitoring-runbook.md. Startup fixture/config issues
were corrected, including finite read-only Grafana provisioning readiness waits.
No external paging/HA, TLS staging, production image/security scan, performance
SLO or T09 implementation is claimed. Only owned disposable lab resources are
removed; dev data and pre-existing .env/.github are untouched. Tracked docs synced;
phase/index corrected locally and remain unstaged. Task commit stays local on
feature/phase-8-production-engineering, no push/merge. At that T08 checkpoint,
next: P8-T09, unstarted.

P8-T09 completed (2026-10-11): fixed CONNECT route and operator proxy DNS
snapshot, required prod proxy configuration, strict all-answer public-unicast
Squid ACLs and isolated internal-app/outbound-proxy networking. Reproduced JDK
Java 21/25 IPv6-literal SNI failures led to explicit user approval of dedicated
Apache Client 5.6.4/Core 5.4.4 Webhook transport, not a runtime/API restriction.
Trusted TLS/IP-SAN, exact UTF-8/HMAC/four headers, no redirects/retries/cookies/auth,
header-only completion/hard cancellation, monotonic deadlines and no-TX remain.
Privacy-safe logging/spans and finite resources are preserved; proxy DNS changes
require client/application restart. No schema, business, financial or event
contract changed; Apache imports are confined to infrastructure.

Final focused: 205 tests/nine classes (44.165s); full clean verify: 1,388 tests/193
suites (8m37s), zero failures/errors/skips, real PostgreSQL/Rabbit. A test-only
reactor startup race was corrected with a bounded assertion before rerunning
both gates. Final Java 21 network fixture passes all 34 independent assertions,
including native IPv6 literal, missing IP-SAN, mixed/delayed/rebound DNS, timeout,
HMAC and denied direct IPv4/IPv6 paths. Affected monitoring smoke passes prod
startup, native provisioning/ingestion, telemetry outage/recovery and unchanged
replay/conflict/balanced duplicate-safe Ledger checks on the same final JAR.
Artifact digest, environment, owned cleanup and retained external synthetic
secrets are recorded in [egress acceptance](webhook-egress.md).
No production image scan, provider deployment, staged firewall, load/SLO or
recovery acceptance is claimed; T10-T18 retain their gates. Commit only T09
tracked files on the phase branch; no push/merge, no T10 work. Ignored phase/index
corrections are verified locally and never staged; user `.env`/`.github/` remain
untouched.

P8-T10 completed (2026-10-13): reviewed cookie CSRF and absent ingress-budget
findings led to explicit user approval of the auth marker/closed-preflight and
NGINX TCP-peer limits/429 contracts. The chain-local decoded-path marker filter
rejects invalid auth POSTs before credential/session work; legitimate auth and
bearer APIs remain intact. Non-root/read-only pinned NGINX 1.30.5 uses bounded
shared auth 10/minute/burst 5 and mutation 10/second/burst 20 budgets, strips
forwarded/rewrite identity and disables upstream retries/429 interception.
Production forwarding strategy is `none`. No Java dependency/schema/financial/
token/event/locking/TX change; source contracts and client fixtures are updated.

Final focused: 266 tests/21 classes (2m06s); full clean verify: 1,405 tests/195
suites (8m21s), zero failures/errors/skips, real PostgreSQL/Rabbit. Final native
policy gate: 26 independent static/runtime assertions plus NGINX config-test;
real prod/DB/broker ingress gate verifies accepted/rejected replays, refill and
exactly one provider transaction/refund with two balanced unique Ledger postings.
Affected monitoring outage/recovery and 34-assertion Webhook network/TLS/HMAC/
bypass regressions pass on the same final JAR. All eight owned test projects are
removed and absence is checked; external synthetic secret directories remain
for manual cleanup, not in the repo. Artifact/resources/limitations are in
[API security acceptance](api-security-review.md). Ignored phase/index are
corrected locally and never staged; `.env`/user `.github/` are untouched.
No T11 implementation, push/merge, TLS staging, supply-chain scan, measured
capacity or global quota is claimed; Phase 8 remains IN PROGRESS.

### P8-T11 implementation checkpoint (2026-10-15)

User-approved adoption of the pre-existing sole CI workflow adds independent
JDK21 `clean verify` and pinned supply-chain gates, read-only credentials,
mandatory fresh/non-skipped coverage for all source suites, bounded native
scanners/negative controls and explicit seven-day safe artifact projections.
No app/POM/schema/API/financial/fixture changes; no runtime dependency upgrades.
28 focused gate tests pass on Windows and Linux; actionlint passes. Full JDK21
Maven verification passes 1,405 tests/195 suites, zero failures/errors/skips,
actual PostgreSQL/RabbitMQ. Native source/dependency/clean/unavailable-DB controls
pass; scan covers 234 Maven components with a fresh valid DB.

Security remains BLOCKED: Gitleaks reports ten baseline detections at seven
locations in five unchanged example/test files; Grype reports 29 candidate
package/advisory matches (6 CRITICAL, 11 HIGH, 11 MEDIUM, 1 LOW). These are scan
findings requiring review, not claims of proven exploitation. The user explicitly
chose no secret exceptions and later review. No dependency exception is granted
either; policy remains empty, all blocking findings stay blocking. Evidence,
artifact/environment, tool scope and owner-side CI limitations are in
[CI supply-chain gates](ci-supply-chain.md). GitHub-hosted CI has not run because
this phase is unpublished; no push/dispatch/merge/deploy/T12 work occurred.
Local phase/index corrections remain ignored and unstaged.

### P8-T12 implementation checkpoint (2026-10-15)

Artifact-first reviewed equivalent packages the exact clean-verified JAR into a
digest-pinned Temurin21.0.12.1 JRE image, non-root Java PID1, read-only/finite
runtime, external persistent keys, no source/local.env/key material bundled.
CI adds image build/runtime/OS+Java scan with explicit safe evidence only, no
registry upload. No Java/POM/financial/API/schema/TX/retry changes.

55 focused controls pass on Windows/Linux; actionlint/Compose validation and
native source/dependency/absent-DB controls pass. Full clean verification passes
1,405 tests/195 suites, no failures/errors/skips, real PostgreSQL/Rabbit. Final
actual image smoke passes active-worker SIGTERM, source commit during broker
outage, persisted key/JWT/replay continuity and recovered duplicate-safe balanced
Ledger/signed TLS Webhooks, with owned resource/secret cleanup.

Security is BLOCKED, not accepted:299 software-component image inventory yields
181 candidate advisory matches (6 CRITICAL,12 HIGH,107 MEDIUM,47 LOW,9 NEGLIGIBLE);
18 HIGH/CRITICAL matches block. Image application-secret scan is clean, but
tracked-source rescan retains10 baseline findings and0 SAST findings. No waiver,
fixture rewrite or dependency upgrade is added. Actual artifact/environment,
native gates and bounded scope are in [container runtime](container-runtime.md).
At this checkpoint T12 was not fully Done and T13 was unstarted; no
push/merge/public/cloud deployment. The later T13 checkpoint below supersedes
only that task status, not the historical scan evidence.
Ignored phase/index are corrected locally and remain unstaged.

### P8-T13 implementation checkpoint (2026-10-17)

Independent immutable-image simulator staging now verifies fresh and Phase7 DB
cases. TLS-only loopback edge, private persistence/monitoring, enforced Webhook
proxy and complete broker bindings/separate DLQs before producers are covered.
Actual dual-stack ingress/private-IP/direct-bypass probes, two-replica telemetry
discovery/dashboard/stored trace, SUCCESS/DECLINED/UNKNOWN and exact replay,
balanced unique Ledger/public events/deliveries, controlled consumer duplicates,
failed-key configuration rollback and graceful restart all pass. Phase7 migration
checks retain V001-V011/checksums, complete financial/ciphertext rows and signing
with the existing key after additive V012/V013. No schema downgrade or financial
reset, historical-binary rollback or external firewall certification is claimed.

83 Python gate controls pass separately on Windows and pinned Linux Python;
native actionlint, Compose, shell, NGINX, promtool and Collector validation pass.
Full clean Java gate passes **1,407 tests /196 suites**, zero failures/errors/
skips with actual PostgreSQL/RabbitMQ. The two new PostgreSQL upgrade checks are
included; focused migration iteration also passed separately. No application
Java/POM/schema/API/TX/locking/state/retry change. A shallow-CI checkout issue and
64MiB proxy cold-start OOM were corrected; the final run uses the verified T08
128MiB Squid baseline and rejects OOM/stopped infrastructure, not a relaxed test.

Artifacts, resources, security boundary and remaining acceptance limits are in
[the staging runbook](staging-deployment.md). All owned synthetic projects,
volumes/networks and external secrets were cleaned; `.env` stays unread and
untracked. Security release remains BLOCKED independently of these functional
results; no waiver, push/merge, cloud account, public endpoint, measured capacity
or T14 implementation is included. Local phase/index corrections stay unstaged.
Fresh current-image scan retains18 blocking matches (6 CRITICAL/12 HIGH) in299
software components; image application-secret scan0. Valid GrypeDBv6.1.10 built
2026-10-16 and native vulnerable/unavailable controls pass. Source804 files retain
10 secret-format/0 SAST findings, with native controls/complete coverage passing.
Both gates remain failed under the no-exception policy; no full Maven dependency
SBOM/T12 broker-outage fixture rerun or clean supply-chain claim is inferred.

### P8-T14 implementation and measured acceptance complete (2026-10-17)

P8-T14 is recorded in [load testing](load-testing.md): frozen open-model
manifest, pinned k6, bounded numeric reports, real retry/drain and whole-project
financial/HMAC/identity audits.114 focused controls pass on Windows and pinned
Linux Python; native k6 protocol/schema and workload inspection pass.

The owner delegated target selection; the unchanged numeric proposal was bound
to calibration before a new acceptance project. A first bootstrap failed before
workload execution and cleaned up. Strict preflight then rejected a4096-byte
Docker MemTotal difference; the actual host was explicitly refrozen and freshly
calibrated, without changing targets. Full report/workload hashes and this
history are retained in the runbook and `ops/load/{proposal,acceptance}.json`.

Independent warmup/steady/peak/stress/30-minute soak acceptance passed all fixed
limits, with0 unexpected server/transport/contract errors,0 drops and all audits
passing. Stress112 policy429 remains separate:8/120 complete workflows, delivery
lag p99 210.47s and drain180.86s. Soak181/181 workflows and3,600 query iterations
complete; aggregate API p95/p99 21.00/52.72ms, worst regular-route p99 124.80ms,
provider p99 upper bound10ms, delivery lag p99 17.25s, drain16.32s and heap-mean
growth0.674MiB. No OOM/restart was observed; sampled pool pending is0.
Final audit:359 payments,358 provider successes,253 refunds,611 unique balanced
postings,1,222 public events and3,666 delivered subscriptions. Owned cleanup
passes. Operational guards are retained, not raised to hide stress; no
unconstrained backend capacity or alert-notification timeline is claimed.

The exact T13 verified1,407-test artifact and production inputs remain unchanged;
no new full Maven verification or security-scan pass is claimed. Source/image
findings remain unwaived and release is BLOCKED. No push/merge or T15 work is
included; local-only phase/index corrections stay unstaged.

### P8-T15 implementation and native acceptance complete (2026-10-17)

[Operational drills](operational-drills.md) implement the declared two-replica
TLS-ingress scenario on the unchanged verified T13 image: concurrent identical
financial requests and original-event redelivery, broker interruption with source
commit/Outbox recovery, DB unavailable readiness/reconnect with exact snapshot
preservation, identified in-flight worker SIGKILL, paused owner/late HTTP result,
slow receiver timeout/real retry, complete monitoring-stack outage/fresh scrape
recovery and one-at-a-time graceful same-image restart under query traffic.

Final unchanged native run passes all eight cases and per-case financial/HMAC/
identity/history audits. Worker kill recovery41.20s, stale-owner recovery39.18s;
abandoned attempts retain unknown HTTP outcomes and cannot be overwritten.
Broker drain21.20s; DB readiness503 at the app/504 at TLS gateway, liveness200,
no financial call offered during DB outage and no data change on reconnect.
Monitoring recovery requires two new post-probe scrapes, not cached UP.
Rolling exits143 for both replicas,38.30/39.98s restart verification, valid
survivor business queries and230 successful/6 explicit transient reads.
Final9 payments/refunds,18 balanced unique postings,36 public events,
18 delivered subscriptions,21 attempts and2 recovered leases; no stuck source/
attempt/backlog/DLQ remains. Owned cleanup passes.

134 focused Python controls pass on Windows and pinned Linux. The T15 host
envelope records actual WSL RAM; it does not alter/reuse T14 exact-host approval
as two-instance performance evidence. Initial readiness/preflight and preliminary
hardening iterations, final report/workload hashes and precise limitations are
recorded in the runbook. Existing T13 verified1,407-test JAR/image and production
inputs remain unchanged: no new full Java gate or security-scan pass is claimed.
Security findings remain unwaived/BLOCKED; no zero-error availability, cloud HA,
restore acceptance, push/merge or T16 implementation. Local phase/index are
corrected separately and stay unstaged.

### P8-T16 implementation and native acceptance complete (2026-10-17)

[Backup/restore](backup-restore.md) records the owner-delegated RPO24h/daily,
7 daily/4 weekly retention and RTO lab15m policy before acceptance. Consistent
PostgreSQL/custom dump, cold same-node/version Rabbit data/definitions and
separately encrypted application keys restore into two fresh empty destinations.
All19 public tables, sequences, schema enforcement and credential definitions
match; PostgreSQL CHECK cast normalization uses a trusted pre-loss schema-only
restore of the exact dump, not skipped checks or source-schema changes.

Final unchanged native cold/mismatch restores pass at101.28/109.23s; snapshot
ages121.42/401.80s meet the declared lab targets, without claiming RPO zero or
post-snapshot commit recovery. The empty-broker mismatch blocks with consumers
disabled before the approved lab-only replay of original envelopes/IDs/headers.
Each recovery has3 payments/refunds,6 balanced unique postings,12 public events,
6 signed delivered subscriptions and6 closed attempts. JWT/API replay,
duplicate/no-extra-HTTP, source trace identity, restart, wrong backup recipient/
tamper, missing/wrong AES and original-key retry/retained history controls pass.
A fourth real workflow passes on the matched destination. Owned cleanup passes.

162 focused controls pass on Windows/pinned Linux; static checks pass. The T13
verified1,407-test artifact is unchanged; no new full Java or clean-security run
is claimed. The runbook records exact hashes, measured host and iteration limits.
Operational off-host storage/scheduling/retention and historical telemetry
backup are not provisioned. Source/image findings stay unwaived/BLOCKED; no
public deployment, push/merge, T17 implementation or phase completion claim.
Local phase/index corrections stay unstaged.

### P8-T17 implementation and owned rehearsal complete (2026-10-17)

[Operational runbook](operations-runbook.md) and
[release checklist](release-checklist.md) cover private management/monitoring,
deployment/compatible config rollback, DB/broker outage, UNKNOWN escalation,
Outbox/DLQ/DEAD triage, existing manual retry, key continuity/exposure, saturation
and backup/restore. Read-only diagnosis, approval-required reversible operations
and irreversible remote/key/data effects are separated. No new admin API,
provider reconciliation, SQL financial repair or automatic broker replay.

The sanitized staging triage tool validates exact UUID project/container labels,
uses bounded read-only SQL/queue/health/metric projections and does not load.env
or expose raw payload/credential/ID data. Final native rehearsal on the unchanged
verified T13 image passes: dashboard list/detail200, DELIVERED retry409, exact
19-table/sequence/schema snapshots unchanged, one payment/refund/two balanced
postings/four public events/two signed delivered subscriptions and financial/
HMAC/source-trace audits. Four queues are empty; actual release preflight stays
BLOCKED. Owned resource/temporary-key cleanup and no-container residue pass.

178 focused tests pass on Windows/pinned Linux;19 final local links/anchors plus source
controller/auth/retry/drain policy checks and static diff checks pass. The runbook
records final command/report hashes and the inherited-probe regression iteration.
No new full Maven/security/load/failure/restore gate is claimed; compiled inputs
and the verified1,407-test T13 artifact remain unchanged. Successful DEAD202 has
existing verified PostgreSQL API coverage, not a newly rehearsed exhaustion case.
No cloud/public deployment, key rotation/live remediation, real DLQ replay,
hosted-CI acceptance, push/merge or T18 implementation. Security, independent
backups/paging/least-privilege and final exit gates remain explicit NO-GO items.
Local phase/index corrections stay unstaged.

### P8-T18 final gate attempt — BLOCKED (2026-10-17)

The [exit audit](production-exit-audit.md) reconciles all phase acceptance groups,
new candidate identities and historical operational evidence. Fresh JDK21
`clean verify` passes1,407 tests/196 required suites,0 failures/errors/skips,
with real PostgreSQL/RabbitMQ and actionlint.182 focused controls pass on
Windows/pinned Linux, including four new fixed-label/privacy staging-probe
regressions; no assertion or timeout is weakened.
Exact non-root final-image build/runtime pass, including
active-worker SIGTERM, broker-outage source/restart, replay/HMAC/key continuity
and owned cleanup. A first generic startup rejection is retained separately;
no fixture, resource or acceptance policy was changed to pass its repetition.
Initial legacy staging proves upgrade/checksum/ciphertext preservation, then
rejects a monitoring assertion with owned cleanup. Repetition passes both
complete fresh/Phase7 cases: private TLS/dual-stack/proxy boundaries, two-replica
telemetry, financial/UNKNOWN/duplicate/HMAC/key and rollback/restart checks.
Both rejected attempts are retained; a pass does not establish their root cause
or waive test-stability review. All owned resources are cleaned.

Fresh valid GrypeDBv6.1.10 built2026-10-17 scans and native canaries run fully:
source10 secret-format/0 SAST findings plus17 HIGH/CRITICAL Maven matches;
image0 application-resource secret findings plus18 HIGH/CRITICAL matches.
Scopes overlap and candidate matches are not proof of exploitability.
Exceptions stay empty and both security gates correctly fail; the real release
preflight stays blocked. No dependency upgrade or fixture suppression is inferred.
Post-diagnostic source-only scanning retains10 secret-format/0 SAST findings;
it does not replace the full Maven security gate or attest a clean final commit.

Clean build inputs and uncompressed JAR entry contents remain unchanged, but
688 ZIP timestamps and the actual JAR/image hashes differ from T13. Unmodified
load/failure/restore preflights refuse the new image before creating resources;
no new30-minute soak, two-instance drill or recovery measurement is claimed.
Old safe reports are preserved separately, never relabeled as new acceptance.
Security remediation requires owner review; the resulting candidate then needs
honestly bound final operational evidence, not rewritten old approvals.

Stale pending-task/lifecycle descriptions are corrected in tracked docs; correct
business/domain/database/API contracts are preserved. No phase freeze, task-DONE
commit, push/merge or external deployment is claimed. Local phase/index edits
remain ignored/unstaged; pre-existing `.env` remains unread and untracked.

### P8-T18 dependency remediation (before bounded source approval)

The owner has now approved reviewing/fixing existing
dependencies and the base image, retaining the no-exception policy and correct
source fixtures. The [security remediation review](dependency-security-review.md)
records the selected candidate and fresh gate status. The preceding T18 evidence
is the pre-remediation attempt, not acceptance of changed dependencies. No old
load/failure/restore approvals are rewritten. Phase8 is still not DONE/FROZEN.
Fresh reviewed-candidate results: full1,407 Java tests/196 suites with zero
failure/error/skip;183 controls on Windows/pinned Linux; exact-image build PASS.
Maven235-component inventory has zero advisory matches. Actual301-component
image scan PASS with zero HIGH/CRITICAL/UNKNOWN, retaining94 MEDIUM/41 LOW/
9 NEGLIGIBLE. Main-Java SAST has zero findings; native adversarial/clean and
vulnerable/unavailable-DB controls pass. Source gate still FAILS on10 secret-format
findings without exceptions. Native runtime PASS with SIGTERM, broker outage/
restart, Ledger/Webhook replay/HMAC, persistent keys and owned cleanup. The first
canonical Phase7 staging attempt passed
legacy data/checksum/key preservation, then failed the Grafana health check;
owned cleanup passed and the rejected report is retained. A read-only diagnostic
repetition passed without reproducing the Grafana failure. The subsequent
unmodified canonical invocation PASSes both Phase7 and fresh: legacy preservation,
TLS/private dual-stack/proxy boundaries, two-replica telemetry, financial/UNKNOWN/
duplicates/HMAC/keys and configuration rollback/restart; both cleanups pass,
no running containers remain. No root cause or fix is inferred for the initial
Grafana rejection, and no resource/assertion/deadline changed.
Unmodified load/failure/restore/runbook entry
points reject the new artifact before workload creation; actual release preflight
FAILS on source security. No old approval or threshold is changed;
this dirty working-tree evidence is not a clean release or task-DONE attestation.

### P8-T18 source secret-finding review (2026-10-18)

The owner authorized continuing individual review, not scanner exceptions.
The [review](secret-findings-review.md) reproduces10 `generic-api-key` report rows
at seven locations in five files. They are test-only synthetic AES settings,
public API-key lookup prefix and an Idempotency-Key example, not production
credentials at those reviewed locations. Main-Java SAST/required coverage and
native adversarial/clean scanner controls pass. No match/secret is exported,
`.env` stays unread, and no fixture, detector or exception is changed. Existing
zero-exception policy still blocks the gate; narrowly accepting these false
positives would require a separate explicit policy decision. No new full Java,
Maven/image scan or long operational acceptance is claimed for this docs-only
review; Phase8 is not DONE/FROZEN and no commit/push/merge is performed.

### P8-T18 owner-approved bounded source review acceptance (2026-10-18)

After the individual review, the owner explicitly authorized exact false-positive
acceptance. `ops/ci/secret-reviews.json` binds only seven test/doc locations and
ten detector rows, rule/path/line/count and LF-normalized whole-file identities,
pinned Gitleaks image/config, review evidence/owner/approval and exclusive30-day
expiry. Expired/future/stale/unmatched/changed/duplicate/malformed reviews fail;
new findings remain blocking. Source scans also reject inline suppression.
No fixture, Java/application configuration, public contract or image-secret gate
changed. Dependency/SAST exceptions stay empty. See the
[approved scope and verification](secret-findings-review.md#approved-bounded-false-positive-acceptance).

203 offline controls pass on Windows and pinned Linux, including20 new
policy regressions. Native scanner controls prove exact synthetic
acceptance and rejection of new inline-suppressed findings, changed contents and
exclusive expiry. The full source/dependency security invocation PASSes:
10 total detector rows,10 accepted under the seven exact reviews, zero unreviewed,
zero SAST/advisory matches,235 Maven components and all native source/dependency/
unavailable-DB controls passed. The real release preflight now rejects dirty
uncommitted source, not source findings. No unchanged full Java suite,
runtime/staging/load/failure/restore rerun, task-DONE commit, push or phase freeze
is claimed by this CI-only change. Historical application evidence remains bound
to the same unchanged verified image; final operational/clean-commit gates remain.

### P8-T18 final-artifact operational reacceptance (in progress)

Original T14 manifest/proposal/approval and T15/T16 prerequisite declarations
are preserved under the respective `ops/*/history/` directories, verified
against the committed baseline after LF normalization. Existing safe reports
were copied to an external local evidence backup before canonical reruns.
The active load manifest binds the security-remediated artifact, not the old
T13 image; proposal/approval now bind fresh completed calibration. Numeric targets,
rates, retries/jitter, resources and the independent 30-minute soak are unchanged.
204 focused controls pass. A calibration bootstrap failed before workload
(RabbitMQ exit1, OOMKilled=false in a later diagnostic repetition); a separate
strict host preflight rejected a 4,096-byte MemTotal change. Both failures are
retained. Fresh exact-host calibration PASSes: zero unexpected/contract/drop
errors, every financial/HMAC/retry audit and cleanup pass. Unchanged numeric
targets were selected after completion under the owner's prior delegation;
an independent acceptance with a full30-minute soak is running. A release-time
expiry gap was hardened: the current clock must precede the earliest accepted
review expiry even when the source scan is fresh; complete finding counts are
required.212 offline controls pass on Windows. The real preflight rejects the
old report lacking expiry metadata; no report is patched, and a fresh full
source scan/clean committed attestation is still needed. No new load pass,
failure/restore acceptance, completed task, commit, push or freeze is claimed.

Continuation on2026-10-18 found the first remediated acceptance interrupted
without a final report. Its process was absent;11 exact-owned stopped services
(one topology exit0, ten exit255, none OOMKilled) were inventoried. Bounded
interruption evidence is retained;11 containers,7 networks,5 volumes and one
external synthetic directory were safely removed and absence checked. A new
empty acceptance invocation repeats all profiles/full1,800-second soak; it does
not join samples or time across attempts. The host/calibration/approval still
match unchanged.212 controls pass on both Windows and pinned Linux; current
full native source scan and final acceptance remain open.

### P8-T18 continuation: final-artifact measurements (2026-10-18)

The new independent load invocation PASSes all profiles and the full30-minute
soak, without joining interrupted samples. Regular load has zero drops/429;
stress retains13 drops and96 policy limits, not a maximum-capacity claim.
All financial/HMAC audits and owned cleanup pass. Report SHA-256:
`1bcff0e7ea8ae5f461e2b4ccd421f438f4826ab375cf7c5538ad9248b09ff0bc`.
The new two-replica failure invocation PASSes all eight unchanged cases and
cleanup; rolling reads retain270 successes/7 transients, not zero downtime.
Report SHA-256:
`13f42a23270a47595abe8a9ea0b7b797f77524096a3ebbda3cdedadcc2d0c04e`.
The matched/mismatch restore repetition PASSes at101.40/110.38s with exact
checkpoint/key/replay/HMAC/financial checks and owned cleanup. Its report hash:
`ffc7fb72836cb09b98ca9b416b4cf735c34fb9e4c28d53195a8b7ccbc4b6208b`.
Native runbook rehearsal PASSes with full persisted snapshots unchanged. All
three dedicated egress/monitoring-alert/real-app-ingress scripts PASS on the
verified JAR/reviewed JRE; resource absence/synthetic-key cleanup is verified.
215 controls pass on Windows and pinned Linux. The new full source/dependency
scan PASSes with complete10/10/0 finding accounting, current exclusive expiry,
zero SAST/advisory matches and all native controls. That report is a dated dirty
source checkpoint, not clean attestation. After the verified candidate commit,
rerun the actual full source gate and release preflight against clean HEAD.
Hosted GitHub CI remains unobserved; owner approval for branch publication was
requested separately, not inferred. Phase remains IN PROGRESS, no task DONE,
freeze, push, merge or deployment is claimed by this documentation checkpoint.

### P8-T18 hosted portability and detector metadata continuation

P8-T18 hosted continuation: owner-approved phase-branch publication at4fa6d30
produced run39105546749. Full Java/build/runtime/fresh-Phase7 staging pass, both
source/image scan steps fail. Native Linux fixtures independently reproduce
report-output UID denial; the corrected scanner identity/cache ownership path
retains the sandbox and policy.219 controls pass on Windows/Linux. Fresh full
UID1001 source/image gates PASS with the original policy and real fresh-DB/
vulnerability/absence controls; new hosted verification remains required; no phase freeze,
merge, deployment, image publication or security waiver is declared.

Fixed-code annotation in run39109618819 identifies source-review-count at
severity policy. Native LF reproduction isolates the unchanged test setting's
two detector spans28–28/28–29. The owner approved canonical LF snapshots and
exact span metadata, retaining all ten rows/seven source positions/five file
identities, scanner/config and the original exclusive expiry. No row is
deduplicated and no finding is accepted by tolerance.228 Windows controls pass;
canonical native/Linux and new hosted acceptance remain required.

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
