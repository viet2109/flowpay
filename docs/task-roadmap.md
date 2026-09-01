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

Status: `IN PROGRESS`.

Completed tasks:

- P6-T01 Phase 5 exit gate and eventing architecture freeze.
- P6-T02 Outbox database migration.
- P6-T03 Versioned integration-event contracts and producer ports.
- P6-T04 Transactional Outbox writer and persistence.

Remaining tasks:

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

Planned tasks:

- endpoint/subscription schema.
- endpoint CRUD.
- secret generation/encryption.
- webhook event materialization.
- delivery queue/state.
- HMAC signing.
- HTTP delivery adapter.
- retry/backoff.
- delivery-attempt history.
- dead state/manual retry.
- dashboard delivery APIs.
- integration tests.

## Phase 8 — Production Engineering

Post-functional-MVP hardening:

- richer domain metrics.
- tracing propagation.
- dashboards.
- load tests.
- security review.
- dependency/security scanning.
- production Docker image.
- deployment.
- backup/recovery documentation.
- operational runbook.

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
