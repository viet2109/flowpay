# FlowPay — Product Requirements

## 1. Product statement

FlowPay is a payment-processing platform for merchants. A merchant integrates one API to create, process, inspect, and refund payments while FlowPay provides reliable state management, transaction safety, event delivery, and a financial ledger.

The MVP does not process real money. It uses a controllable payment-provider simulator so the system can reproduce success, decline, timeout, retry, and unknown-outcome scenarios.

## 2. Portfolio goal

FlowPay is intentionally designed to demonstrate backend engineering beyond CRUD:

- Java and Spring Boot fundamentals.
- Transaction boundaries and consistency.
- Concurrency protection.
- Idempotent financial operations.
- Payment state machines.
- Reliable event delivery.
- Transactional outbox.
- RabbitMQ and eventual consistency.
- Double-entry ledger concepts.
- Security and secret handling.
- Observability and production-oriented testing.

## 3. Actors

### Merchant Admin

A human user who signs in to the FlowPay dashboard and manages:

- Merchant profile.
- API keys.
- Webhook endpoints.
- Payment and refund history.
- Webhook delivery history.

### Merchant Backend

The merchant's server that authenticates with a FlowPay API key and calls payment/refund APIs.

### Customer

The person paying the merchant. The MVP does not require a FlowPay customer account.

### Payment Provider

An external payment processor. The MVP initially uses `SIMULATOR`.

### FlowPay Admin

Internal operational actor reserved for later versions. An admin console is not required for the MVP.

## 4. MVP functional scope

### Identity and merchant

- Register a dashboard user and merchant.
- Create the initial merchant membership as `OWNER`.
- Login using email/password.
- JWT access tokens.
- Opaque rotating refresh tokens.
- Logout and refresh-token revocation.
- Merchant profile.
- Merchant API key creation, listing, revocation, and authentication.

Email verification, password-reset email, Google OAuth, and member invitations are not part of MVP Phase 1.

### Payment

- Create `PaymentIntent`.
- Confirm payment.
- Retrieve payment.
- List/filter payments.
- Track provider attempts through `PaymentTransaction`.
- Correctly represent provider `SUCCESS`, `DECLINED`, and `UNKNOWN` outcomes.

### Idempotency

Create and confirm Payment commands require `Idempotency-Key`.

The system guarantees:

- Same merchant + operation + key + equivalent request returns the original logical result.
- Reusing a key with a different request is rejected.
- Database uniqueness protects against concurrent duplicate requests.
- A completed replay preserves the original public response status and body.
- Concurrent confirmation never invokes the provider more than once for the same scoped request.

### Refund

- Full refund.
- Partial refund.
- Multiple partial refunds.
- Protection against concurrent over-refund.
- Refund status and history.

### Ledger

- Double-entry posting.
- Debit total equals credit total for a ledger transaction.
- Posted ledger data is append-only.
- Corrections are represented as reversal postings.

### Outbox and messaging

- Reliable integration events.
- Transactional outbox.
- RabbitMQ transport.
- Consumers must tolerate at-least-once delivery.

### Webhooks

- Merchant webhook endpoint configuration.
- Event subscriptions.
- HMAC-SHA256 signature.
- Retry with backoff.
- Delivery attempt history.
- Dead delivery state.
- Manual retry for dead deliveries.

## 5. Payment provider simulator

The simulator must eventually support deterministic test scenarios such as:

- `SUCCESS`
- `DECLINED`
- `TIMEOUT`
- `UNKNOWN`

A timeout is not automatically a payment failure. If the provider outcome cannot be known, the transaction becomes `UNKNOWN` and the payment remains `PROCESSING` until reconciliation/inquiry resolves it.

## 6. MVP roadmap

### Phase 0 — Foundation

Spring Boot foundation, configuration, Docker, Flyway, package boundaries, API/error foundation, correlation IDs, observability, Testcontainers, ArchUnit, and CI.

### Phase 1 — Identity & Merchant

User, merchant, membership, login, JWT, rotating refresh token, merchant dashboard security, API-key management, and API-key authentication.

### Phase 2 — Payment Core

Status: `DONE/FROZEN` as of 2026-08-30.

Payment intent, payment transaction, provider port/simulator, confirmation flow, unknown outcomes, and payment query APIs.

### Phase 3 — Idempotency

Status: `DONE/FROZEN` as of 2026-08-31.

Idempotency records and replay/conflict semantics for financial commands.

### Phase 4 — Refund & Concurrency

Status: `DONE/FROZEN` as of 2026-09-01.

Full/partial refunds, refund reservation, concurrency protection, and integration tests.

### Phase 5 — Ledger

Status: `DONE/FROZEN` as of 2026-09-01.

Double-entry Ledger accounts, immutable balanced transactions and entries,
concurrency-safe account provisioning, deterministic duplicate-safe posting,
Payment/Refund success accounting, and reversal postings. Phase 5 introduced a
transitional synchronous local posting trigger. Phase 6 supersedes that trigger
with Outbox-backed asynchronous delivery while preserving the Phase 5 Ledger
posting and duplicate-safety rules.

### Phase 6 — Outbox & RabbitMQ

Status: `DONE/FROZEN` as of 2026-09-02.

Transactional Outbox persistence, confirmed RabbitMQ publication, retryable
relay, at-least-once delivery, bounded consumer retry/dead-lettering, and
idempotent eventual Ledger posting for versioned Payment/Refund success events.

### Phase 7 — Webhooks

Status: `DONE/FROZEN` as of 2026-10-04 after P7-T16's clean quality gate.
The functional simulator MVP is complete; production readiness is not implied.

The complete six-event Payment/Refund catalog writes processing, success, and
known failure facts atomically through Outbox, preserving UNKNOWN semantics and
the existing success-only Ledger consumer. V010/V011 implement endpoint,
subscription, immutable event, leased delivery, and retained attempt schemas.
Dashboard JWT APIs create/list/get/update/soft-disable endpoints and rotate
AES-256-GCM-protected signing secrets, with tenant ownership and concurrency
protection. Secrets are returned once on create/rotate, never on reads.

RabbitMQ materialization handles all six types, snapshots ACTIVE subscriptions
only once per source event, and commits event/delivery rows before ACK. Equivalent
redelivery does not recompute subscriptions; malformed/contradictory messages
follow bounded consumer retry to the independent Webhook DLQ.

The enabled worker claims each due delivery and OPEN attempt transactionally,
sends exact-body HMAC-signed HTTP outside a DB transaction with URL revalidation,
bounded timeouts and no redirects, then fences finalization by attempt number.
Five jittered retry delays give six normal attempts; exhausted deliveries become
DEAD. Expired leases recover abandoned attempts without fabricating HTTP results.
Disabling an endpoint atomically cancels scheduled work; an in-flight request may
finish with its claimed URL/secret snapshot. Later attempts use current secrets.

Dashboard delivery list/detail APIs expose filters, pagination, safe diagnostics
and ordered attempt history. Manual retry returns 202, requires an owned DEAD
delivery and ACTIVE endpoint, and retains counts/history without synchronous
HTTP. External delivery is at-least-once with stable event ID/body and merchant
dedupe, not guaranteed ordering or exactly-once processing. Delivery failures
do not mutate Payment, Refund, Ledger, or published Outbox records. Clean
migration, E2E, crash/concurrency, security and architecture regressions cover
these boundaries. Phase 6 remains DONE/FROZEN; Phase 8 hardening is not included.

### Phase 8 — Production engineering

Status: `IN PROGRESS` as of 2026-10-17; P8-T01 baseline/design, P8-T02
production configuration/management, P8-T03 business metrics, P8-T04
asynchronous operational metrics, P8-T05 synchronous tracing and P8-T06 durable
Outbox trace context, P8-T07 consumer/delayed-worker tracing, P8-T08 monitoring
lab/dashboards/alerts, P8-T09 Webhook egress protection and P8-T10 reviewed auth/
ingress security controls complete. T11–T17 gate implementations and bounded
staging/load/failure/restore/runbook evidence are recorded below. T18 final
acceptance is in progress, not phase completion or security clearance.

Target selected by the user: reproducible staging on Linux/container, without
a chosen provider or external deployment. The Phase 7 release is merged into
remote main, the phase branch is isolated, and the fresh 1,173-test baseline
gate passes with zero failures/errors/skips. P8-T01 introduces documentation
only. P8-T02 adds fail-closed production startup, a dedicated loopback-by-default
management scrape listener, public Actuator denial, DB/availability readiness,
process liveness and bounded HTTP graceful shutdown. Broker/telemetry outages
do not block durable source + Outbox acceptance; no business contract changed.
Staging network and container/worker lifecycle proof remain separate gates.

P8-T03 adds bounded, failure-isolated Payment/Refund outcome and provider-latency
metrics plus Idempotency decisions/committed reservations. Logical counters are
after-commit, not financial truth or HTTP success accounting; rollback/replay
does not inflate them. UNKNOWN semantics are preserved. No new dependency,
schema, business state/API/event, lock or transaction boundary is introduced.

P8-T04 adds cached shared-DB Outbox/Webhook backlog/age projections plus bounded
Outbox publication, Ledger posting/duplicate and Webhook attempt/HTTP/lease
observations. Persisted success counters follow commit/fencing. Sampling is
read-only, throttled, separate from financial orchestration and safe on failure;
stale/shared gauge semantics and query costs are documented. No schema,
business contract, retry budget, locking or transaction-boundary change.

P8-T05 adds synchronous HTTP/provider/Webhook tracing using the installed SDK,
bounded privacy-safe export and distinct request/trace/span log correlation.
Collector failure does not change financial results, and signed merchant HTTP
does not receive internal context headers. Root sampling and opt-in export are
explicit. No dependency, schema, payload or financial transaction change.

P8-T06 adds reviewed nullable technical Outbox metadata (V012) and validated
source traceparent propagation in Rabbit headers across retry/restart. Legacy/
malformed context is ignored; no baggage/tracestate, V1 payload/public Webhook
change, dependency or financial TX/locking/retry change. Full clean verify
passes 1,331 tests/187 suites with zero failures/errors/skips. Publication,
consumer and delayed-worker spans still belong to T07, not this task.

P8-T07 implements bounded publication/consumer/delivery/recovery spans and
restart-stable Webhook-owned technical context (V013). Parent/cleanup/privacy
policy is explicit; public IDs/JSON/HMAC/headers, financial/dedupe/ACK/retry/lease
semantics are preserved. Full clean verify passes 1,351 tests/191 suites with
no failures/errors/skips. This is not deployed collector/staging evidence.

P8-T08 provides an independent Linux-container simulator monitoring lab with
digest-pinned Prometheus/Grafana/Collector/Tempo, versioned dashboard/datasource
provisioning, 12 alert rules with tested firing/resolution and runbook references.
Management/broker/DB/collector remain private; Grafana is loopback/authenticated.
Real metric/trace ingestion and all-monitoring outage/recovery preserve payment,
refund, Idempotency and balanced duplicate-safe Ledger outcomes. This is not
external paging, a production image, TLS staging or measured performance evidence.
No application defaults, API/event/schema or financial semantics change.

P8-T09 implements and verifies Webhook effective-destination/egress enforcement
with a fixed CONNECT proxy and dedicated user-approved Apache transport on Java
21. Real isolated DNS/TLS/IP-SAN/HMAC and IPv4/IPv6 bypass fixtures pass; affected
monitoring/financial wiring is reverified on the final artifact. This is not
external-provider deployment or actual staging-firewall acceptance.

P8-T10's documented cookie CSRF/ingress findings and explicit user-approved
mitigations are verified: required auth POST marker/closed preflight and bounded
TCP-peer-IP edge limits with safe correlated 429/Retry-After. Final 266 focused
and 1,405 full tests, native edge/real-app financial/replay gates, monitoring and
Webhook egress regressions pass without weakening token/financial/TX semantics.
The single-edge/per-IP, same-origin UI and pending TLS/scan/capacity limitations
have explicit owners in `api-security-review.md`; this is not real-money readiness.

P8-T11 implements CI/supply-chain gates with approved workflow adoption,
mandatory real infrastructure coverage, pinned scanners/negative controls and
safe SBOM/evidence. Gate implementation is complete, **security acceptance is
BLOCKED**: ten baseline secret-format detections and 17 HIGH/CRITICAL dependency
findings remain unwaived; the user chose later review, not fixture rewrites or
scanner bypass. Full Java verification passes 1,405 tests/195 suites with no
failures/errors/skips. This is not a clean security or hosted-CI acceptance claim.
See [CI scope, policy and evidence](ci-supply-chain.md).

P8-T12 adds an artifact-bound non-root Java21 runtime image, finite runtime
budgets, external secret/key continuity and isolated API/Ledger/Webhook recovery
gates. Image security remains a separate fail-closed release gate; see
[runtime contract and current acceptance](container-runtime.md). No provider,
registry publication, public API hosting or frontend deployment is selected.

P8-T13 supplies [isolated TLS staging](staging-deployment.md), private
persistence/broker/telemetry, external key continuity, topology-before-producers
and fresh/Phase7 upgrade/recovery verification. This local simulator environment
does not grant release clearance while T11/T12 security gates remain blocked.

T14's [declared load/30-minute soak](load-testing.md), T15's
[two-instance failure drills](operational-drills.md), T16's
[DB/broker/key restore](backup-restore.md) and T17's
[operational runbooks](operations-runbook.md) have bounded lab acceptance on
their recorded T13 artifact. They do not transfer automatically to a different
final image or host, waive source/image findings, provision off-host backups or
provide public hosting. T18 must reconcile all required final-artifact gates.
Specific tool versions, trace DDL/security contract changes and measured
performance/recovery targets have explicit owner/review gates in
`production-engineering.md`; they are not silently preimplemented or fabricated.
Public financial/event contracts and Phase 7 reliability semantics remain frozen.

The [P8-T18 exit audit](production-exit-audit.md) reconciles the current final
candidate with those historical proofs and independently required gates. The
2026-10-18 candidate repeats full30-minute load/soak, all eight two-instance
failure cases, matched/mismatch restores and read-only runbook rehearsal with
passing financial/HMAC/cleanup audits. Stress drops/429 and rolling transients
remain explicit. Final native/source/clean-commit and hosted-CI evidence are
separate, not inferred from these operational passes. The audit
records no security waiver, public deployment or phase freeze.

Completion is evidence-bound to the selected staging target. It does not imply
real-money readiness, an unspecified cloud deployment, exactly-once HTTP or
zero-data-loss guarantees. Phase 8 is DONE/FROZEN only after P8-T18.

## 7. Explicitly out of scope for MVP

- Real card storage.
- Real bank/card processing.
- KYC.
- Fraud/ML engine.
- Chargebacks and disputes.
- Settlement and payouts.
- Multi-provider smart routing.
- Subscription billing.
- Foreign-exchange conversion.
- Multi-region deployment.
- Kafka.
- Elasticsearch/OpenSearch.
- Redis unless a later requirement justifies it.
- Microservice decomposition.

## 8. Post-MVP direction

The preferred evolution is:

1. Multi-provider routing.
2. Fees and merchant balances.
3. Settlement/payout.
4. Provider reconciliation.
5. Recurring billing.
6. Disputes and chargebacks.
7. Risk/fraud.
8. Analytics/search.
9. Service decomposition when domain and scale justify it.

The MVP architecture must not intentionally block these extensions, but it must not implement them prematurely.
