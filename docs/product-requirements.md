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

Status: `IN PROGRESS` as of 2026-09-13.

Endpoint configuration, subscriptions, immutable event materialization, signed
at-least-once HTTP delivery, leased multi-instance claiming, retry/backoff,
delivery history, and dead delivery handling. Architecture/contracts are frozen,
the V010 endpoint/subscription schema is complete, and endpoint lifecycle, URL
policy, and secret cryptography are implemented.

### Phase 8 — Production engineering

Hardening after the functional MVP: richer tracing/metrics, load testing, security hardening, deployment improvements, and operational tooling.

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
