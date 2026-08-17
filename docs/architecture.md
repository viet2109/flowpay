# FlowPay — Architecture v1

## Architectural Style

FlowPay v1 is a modular monolith.

Goals:

- strong module boundaries;
- simple deployment while the domain evolves;
- explicit transaction ownership;
- later extraction of selected services without rewriting domain concepts.

## Source Structure

```text
src/main/java/com/flowpay
├── FlowPayApplication.java
├── common
├── infrastructure
├── identity
├── merchant
├── payment
├── refund
├── ledger
└── webhook
```

Typical business module:

```text
payment/
├── api/
├── application/
├── domain/
└── infrastructure/
```

## Layer Responsibilities

### API

- HTTP endpoints;
- validation;
- authentication context extraction;
- request mapping;
- response mapping.

### Application

- use-case orchestration;
- transaction boundaries;
- command/query handling;
- calls to domain models and ports.

### Domain

- aggregates;
- value objects;
- state transitions;
- business invariants;
- domain events;
- repository abstractions where appropriate.

### Infrastructure

- JPA entities/repositories/adapters;
- provider adapters;
- RabbitMQ;
- security implementation;
- outbox relay;
- observability;
- scheduling.

## Transaction Strategy

External provider calls must not run inside an open database transaction.

Payment confirmation conceptually uses:

```text
TX1
  PaymentIntent CREATED -> PROCESSING
  create PaymentTransaction
  commit

NO DB TX
  call provider

TX2
  finalize PaymentTransaction
  finalize PaymentIntent
  append outbox event
  commit
```

For ambiguous provider response:

```text
PaymentTransaction -> UNKNOWN
PaymentIntent      -> PROCESSING
```

## Transactional Outbox

Business modules publish integration events through an abstraction.

The default implementation inserts `outbox_events` in the same PostgreSQL transaction as the business state change.

An outbox relay publishes to RabbitMQ asynchronously.

Business code must not publish directly to RabbitMQ.

## Event Consumers

Initial event-driven flows:

```text
PaymentSucceeded
├── Ledger consumer
└── Webhook consumer

RefundSucceeded
├── Ledger consumer
└── Webhook consumer
```

Consumers must be idempotent.

## Persistence Model

Use PostgreSQL + Flyway.

Persistence strategy:

```text
Internal PK        BIGINT identity
Public IDs         prefixed ULID-like strings
Money              BIGINT minor units
Currency           CHAR(3)
Timestamps         TIMESTAMPTZ
Mutable aggregate  optimistic version BIGINT
```

Cross-module database foreign keys are allowed while all modules share one database.

Cross-module JPA associations are forbidden.

## Domain vs Persistence Objects

For important financial aggregates, prefer separating domain models from JPA entities.

Example:

```text
PaymentIntent
   <-> PaymentIntentPersistenceMapper
   <-> PaymentIntentEntity
```

Do not apply this mechanically to every technical record if it adds no domain value.

## Security Context

Do not parse JWT/API-key authentication throughout business services.

Expose small abstractions such as:

```text
CurrentUserProvider
CurrentMerchantProvider
```

## Payment Provider Port

Application/domain code depends on a provider abstraction.

Infrastructure implements the simulator and any future real adapters.

Provider-specific statuses are normalized before entering core business logic.

## Observability Direction

Structured logs should include public identifiers and request correlation where useful:

```text
requestId
merchantId
paymentId
paymentTransactionId
refundId
eventId
```

Never log secrets.

Later phases should add:

- OpenTelemetry traces;
- Prometheus metrics;
- Grafana dashboards.

## Testing

- domain unit tests without Spring;
- application service tests with mocked ports;
- PostgreSQL integration tests with Testcontainers;
- RabbitMQ integration tests with Testcontainers when messaging is involved;
- ArchUnit module-boundary tests.
