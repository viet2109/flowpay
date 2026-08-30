# FlowPay — Phase 2: Payment Core

Status: `DONE/FROZEN` as of 2026-08-30.

## 1. Goal

Build the complete Payment Core while preserving the architecture and security
guarantees established in Phase 1.

At the end of Phase 2, the application layer must support:

```text
Merchant API Key
       ↓
MerchantApiPrincipal
       ↓
Create PaymentIntent
       ↓
CREATED
       ↓
Confirm Payment
       ↓
TX1
PaymentIntent → PROCESSING
PaymentTransaction → PROCESSING
COMMIT
       ↓
Payment Provider Simulator
(NO DATABASE TRANSACTION)
       ↓
TX2
       ↓
SUCCESS  → Payment SUCCEEDED  / Transaction SUCCEEDED
DECLINED → Payment FAILED     / Transaction FAILED
UNKNOWN  → Payment PROCESSING / Transaction UNKNOWN
```

Phase 2 must demonstrate:

- Payment state-machine enforcement.
- Merchant ownership isolation.
- Payment-provider abstraction.
- Correct database transaction boundaries.
- External provider calls outside database transactions.
- Optimistic concurrency protection.
- Correct handling of unknown provider outcomes.
- Payment persistence.
- Payment query APIs.
- Provider simulator behavior.

---

## 2. Entry Criteria

Phase 2 may start only when the Phase 1 Exit Gate passes.

Required:

- User registration works.
- Merchant + OWNER are created atomically.
- Passwords are BCrypt hashed.
- Login issues JWT access tokens.
- Refresh tokens are opaque and hashed at rest.
- Refresh rotation is single-use.
- Logout revokes refresh tokens.
- Dashboard merchant APIs require JWT.
- Merchant profile works.
- API keys are generated securely.
- Raw API keys are shown only once.
- API-key revocation works.
- API-key authentication is operational.
- Suspended/closed merchants cannot authenticate using API keys.
- `MerchantApiPrincipal` contains the merchant public ID.
- No cross-module repository/entity access exists.
- No cross-module JPA associations exist.
- No internal database IDs are exposed through HTTP DTOs.
- Flyway owns the schema.
- Phase 1 migrations apply successfully from a clean PostgreSQL database.
- PostgreSQL Testcontainers tests pass.
- Security/integration tests pass.
- ArchUnit passes.
- `./mvnw clean verify` passes.

If `MerchantAccessApi` does not exist yet, P2-T01 must add it before Payment
implementation begins.

---

## 3. In Scope

Phase 2 includes:

- Merchant-to-Payment internal access contract.
- Payment database migration.
- Money and Payment shared types.
- `PaymentIntent` aggregate.
- `PaymentTransaction` aggregate.
- Payment persistence ports and adapters.
- Merchant ownership resolution.
- Create PaymentIntent application use case.
- Payment query/read APIs.
- `PaymentProviderPort`.
- Payment Provider Simulator.
- Confirmation preparation transaction.
- Confirmation finalization transaction.
- Confirmation orchestration.
- Transaction-boundary tests.
- Concurrency tests.
- Security/isolation tests.
- Phase 2 quality gate.

---

## 4. Out of Scope

Phase 2 does not implement:

```text
Idempotency                  → Phase 3
Refund                       → Phase 4
Ledger                       → Phase 5
Outbox                       → Phase 6
RabbitMQ business events     → Phase 6
Webhook                      → Phase 7

Redis                        → not required
Kafka                        → not required
WebFlux                      → not required
QueryDSL                     → not required yet
Resilience4j                 → not required yet
Real payment provider        → not yet
```

RabbitMQ may already exist in project dependencies, but Payment Core must not
publish business messages directly.

The following public write endpoints are also out of scope until Phase 3 adds
Idempotency:

```http
POST /api/v1/payment-intents
POST /api/v1/payment-intents/{id}/confirm
```

---

## 5. Frozen Decisions

### PaymentIntent State Machine

```text
CREATED
   │ confirm
   ▼
PROCESSING
   ├──────────────► SUCCEEDED
   ├──────────────► FAILED
   └──────────────► PROCESSING
                       +
                 PaymentTransaction UNKNOWN
```

Allowed:

```text
CREATED → PROCESSING
PROCESSING → SUCCEEDED
PROCESSING → FAILED
```

Rejected:

```text
SUCCEEDED → PROCESSING
FAILED → PROCESSING
PROCESSING → PROCESSING
CREATED → SUCCEEDED
CREATED → FAILED
SUCCEEDED → FAILED
FAILED → SUCCEEDED
```

`FAILED` is terminal for the current PaymentIntent in the MVP.

A merchant that wants another payment attempt after terminal failure creates a
new PaymentIntent.

### Unknown Provider Outcome

A timeout or lost response must not automatically become a payment failure.

```text
PaymentTransaction = UNKNOWN
PaymentIntent      = PROCESSING
```

There is no `PaymentStatus.UNKNOWN`.

### Provider Outcomes

```text
SUCCESS
DECLINED
UNKNOWN
TECHNICAL_FAILURE
```

| Outcome | Meaning |
|---|---|
| SUCCESS | Provider explicitly confirms success |
| DECLINED | Provider explicitly rejects the payment |
| UNKNOWN | Provider may have processed the request, but FlowPay cannot determine the result |
| TECHNICAL_FAILURE | FlowPay knows the provider operation did not complete |

### Payment Attempts

The schema and domain support multiple provider attempts.

Phase 2 uses only:

```text
attemptNo = 1
```

No arbitrary retry workflow is implemented in Phase 2.

### Public Write APIs

Phase 2 implements:

```text
CreatePaymentIntent
ConfirmPayment
```

at application level only.

The HTTP POST endpoints remain disabled until Phase 3 adds Idempotency.

### Public Read APIs

Phase 2 exposes:

```http
GET /api/v1/payment-intents
GET /api/v1/payment-intents/{id}
GET /api/v1/payment-intents/{id}/transactions
```

These require merchant API-key authentication.

### Transaction Boundary

Payment confirmation must always follow:

```text
TX1
prepare PROCESSING state
COMMIT

NO DATABASE TRANSACTION
call provider

TX2
finalize provider result
COMMIT
```

---

## 6. Task Overview

| Task | Name | Depends on |
|---|---|---|
| P2-T01 | Phase 1 Compatibility & Merchant Contract | Phase 1 Exit Gate |
| P2-T02 | Payment Database Migration | P2-T01 |
| P2-T03 | Money & Payment Shared Types | P2-T01 |
| P2-T04 | PaymentIntent Aggregate | P2-T03 |
| P2-T05 | PaymentTransaction Aggregate | P2-T03 |
| P2-T06 | Payment Persistence Adapters | P2-T02, P2-T04, P2-T05 |
| P2-T07 | Merchant Ownership Integration | P2-T01, P2-T06 |
| P2-T08 | Create PaymentIntent Use Case | P2-T04, P2-T06, P2-T07 |
| P2-T09 | Payment Query & Read APIs | P2-T06, P2-T07 |
| P2-T10 | PaymentProviderPort + Simulator | P2-T03 |
| P2-T11 | Prepare Confirmation Transaction | P2-T04, P2-T05, P2-T06, P2-T07 |
| P2-T12 | Finalize Confirmation Transaction | P2-T04, P2-T05, P2-T06, P2-T10 |
| P2-T13 | Confirm Payment Orchestration | P2-T10, P2-T11, P2-T12 |
| P2-T14 | Integration, Security & Concurrency Tests | P2-T01–P2-T13 |
| P2-T15 | Quality Gate & Documentation | P2-T14 |

---

## 7. Tasks

## P2-T01 — Phase 1 Compatibility & Merchant Contract

### Goal

Ensure Phase 1 exposes the Merchant contract required by Payment without
allowing Payment to depend on Merchant persistence.

### Scope

Implement or verify:

- `MerchantApiPrincipal`.
- `MerchantAccessApi`.
- Immutable active-merchant snapshot/result.
- Merchant status validation for integration operations.

### Dependencies

- Phase 1 Exit Gate.

### Requirements

`MerchantApiPrincipal` must contain:

- Merchant public ID.
- API-key public ID.

Payment must be able to resolve the authenticated merchant public ID into the
internal merchant ID required for database relationships.

Suggested contract:

```java
public interface MerchantAccessApi {

    ActiveMerchantSnapshot requireActiveMerchant(
            String merchantPublicId
    );
}
```

Example result:

```java
public record ActiveMerchantSnapshot(
        Long internalId,
        String publicId
) {}
```

Only `ACTIVE` merchants may be resolved.

Reject:

- `SUSPENDED`.
- `CLOSED`.

### Architecture Constraints

- Payment must not inject `MerchantJpaRepository`.
- Payment must not import `MerchantEntity`.
- Payment must not depend on `merchant.infrastructure.*`.
- Merchant persistence remains owned by the Merchant module.
- Public module APIs must not expose JPA entities.
- Internal IDs returned by internal module APIs must never reach HTTP DTOs.

### API / Persistence Changes

API:

- N/A.

Persistence:

- N/A unless an approved Phase 1 correction is required.

### Flow / State / Transaction

```text
MerchantApiPrincipal
       ↓ merchantPublicId
MerchantAccessApi
       ↓
ActiveMerchantSnapshot
       ↓ merchantInternalId
Payment
```

### Tests

- ACTIVE merchant resolves successfully.
- SUSPENDED merchant is rejected.
- CLOSED merchant is rejected.
- Merchant persistence objects are not exposed.
- Phase 1 tests remain green.
- ArchUnit prevents Payment → Merchant infrastructure dependency.

### Non-goals

- Do not implement Payment entities.
- Do not implement Payment APIs.
- Do not implement member management.
- Do not redesign API-key authentication beyond what this contract requires.

### Done

- Stable Merchant access contract exists.
- No cross-module persistence dependency exists.
- Phase 1 Exit Gate still passes.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T02 — Payment Database Migration

### Goal

Create the PostgreSQL schema required by Payment Core.

### Scope

Create:

```text
V005__payments.sql
```

with:

- `payment_intents`.
- `payment_transactions`.
- Foreign keys.
- Constraints.
- Indexes.

If the repository already uses another migration number, use the next valid
Flyway version and never renumber an applied migration.

### Dependencies

- P2-T01.

### Requirements

#### payment_intents

```text
id                         BIGINT PK
public_id                  VARCHAR NOT NULL UNIQUE
merchant_id                BIGINT NOT NULL
merchant_order_id          VARCHAR NULL
description                VARCHAR NULL
amount_minor               BIGINT NOT NULL
currency                   CHAR(3) NOT NULL
status                     VARCHAR NOT NULL
refunded_amount_minor      BIGINT NOT NULL DEFAULT 0
refund_reserved_minor      BIGINT NOT NULL DEFAULT 0
created_at                 TIMESTAMPTZ NOT NULL
updated_at                 TIMESTAMPTZ NOT NULL
version                    BIGINT NOT NULL DEFAULT 0
```

FK:

```text
merchant_id → merchants.id
```

Constraints:

```sql
CHECK (amount_minor > 0);

CHECK (
    refunded_amount_minor >= 0
    AND refund_reserved_minor >= 0
    AND refunded_amount_minor + refund_reserved_minor <= amount_minor
);
```

Indexes:

```text
UNIQUE(public_id)
INDEX(merchant_id, created_at DESC)
INDEX(merchant_id, status, created_at DESC)
INDEX(merchant_id, merchant_order_id)
```

`merchant_order_id` must not be unique.

#### payment_transactions

```text
id                         BIGINT PK
public_id                  VARCHAR NOT NULL UNIQUE
payment_intent_id          BIGINT NOT NULL
attempt_no                 INTEGER NOT NULL
provider                   VARCHAR NOT NULL
provider_transaction_id    VARCHAR NULL
status                     VARCHAR NOT NULL
failure_code               VARCHAR NULL
failure_message            VARCHAR NULL
started_at                 TIMESTAMPTZ NULL
completed_at               TIMESTAMPTZ NULL
created_at                 TIMESTAMPTZ NOT NULL
updated_at                 TIMESTAMPTZ NOT NULL
version                    BIGINT NOT NULL DEFAULT 0
```

FK:

```text
payment_intent_id → payment_intents.id
```

Constraints:

```sql
CHECK (attempt_no > 0);
```

```text
UNIQUE(payment_intent_id, attempt_no)
```

A partial unique index on:

```text
(provider, provider_transaction_id)
```

may be added only when provider semantics guarantee uniqueness.

### Architecture Constraints

- Flyway owns schema changes.
- Do not edit previously applied migrations.
- Hibernate remains `ddl-auto=validate`.
- Do not introduce cross-module JPA associations.
- Do not add Phase 3+ tables.

### API / Persistence Changes

API:

- N/A.

Persistence:

- Add `payment_intents`.
- Add `payment_transactions`.

### Flow / State / Transaction

N/A.

### Tests

Using PostgreSQL Testcontainers:

- Clean migrations succeed.
- `amount_minor <= 0` is rejected.
- Negative refunded/reserved values are rejected.
- `refunded + reserved > amount` is rejected.
- Duplicate PaymentIntent public ID is rejected.
- Duplicate PaymentTransaction public ID is rejected.
- Duplicate `(payment_intent_id, attempt_no)` is rejected.
- Invalid merchant FK is rejected.
- Invalid payment FK is rejected.

### Non-goals

- Do not implement repositories.
- Do not implement aggregates.
- Do not implement Idempotency.
- Do not create Refund/Ledger/Outbox/Webhook tables.

### Done

- Payment schema matches the specification.
- Clean migration through Phase 2 succeeds.
- Migration tests pass.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T03 — Money & Payment Shared Types

### Goal

Define the shared value types and enums required by Payment Core.

### Scope

Implement or reuse:

- `Money`.
- Currency representation.
- `PaymentStatus`.
- `PaymentTransactionStatus`.
- `ProviderOutcome`.

### Dependencies

- P2-T01.

### Requirements

Canonical financial representation:

```text
long amountMinor
ISO-4217 currency
```

Money should support, where applicable:

- addition.
- subtraction.
- comparison.
- same-currency validation.
- positive-value validation.

Payment statuses:

```text
CREATED
PROCESSING
SUCCEEDED
FAILED
PARTIALLY_REFUNDED
REFUNDED
```

Transaction statuses:

```text
PENDING
PROCESSING
SUCCEEDED
FAILED
UNKNOWN
```

Provider outcomes:

```text
SUCCESS
DECLINED
UNKNOWN
TECHNICAL_FAILURE
```

### Architecture Constraints

- Never use `float` or `double` for financial amounts.
- Canonical domain/persistence amount is minor-unit `long`.
- Do not duplicate an existing compliant Money type.
- Money must remain generic and not contain Payment state-machine logic.

### API / Persistence Changes

API:

- N/A.

Persistence:

- N/A.

### Flow / State / Transaction

N/A.

### Tests

- Same-currency addition.
- Subtraction.
- Comparison.
- Currency mismatch is rejected.
- Invalid currency is rejected.
- Positive-value validation works.

### Non-goals

- Do not implement PaymentIntent.
- Do not implement persistence.
- Do not implement providers.
- Do not implement refund behavior.

### Done

- One canonical Money representation exists.
- Required Payment enums exist.
- Unit tests pass.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T04 — PaymentIntent Aggregate

### Goal

Implement PaymentIntent as the aggregate that owns Payment business state.

### Scope

Implement:

- `PaymentIntent`.
- Creation/rehydration model.
- Phase 2 state transitions.
- Domain tests.

### Dependencies

- P2-T03.

### Requirements

Conceptual state:

```text
internalId
publicId
merchantId
merchantOrderId
description
Money amount
status
refundedAmount
refundReservedAmount
version
createdAt
updatedAt
```

Creation produces:

```text
status = CREATED
refundedAmount = 0
refundReservedAmount = 0
```

Required behavior:

```text
startProcessing()
markSucceeded()
markFailed()
```

Allowed transitions:

```text
CREATED → PROCESSING
PROCESSING → SUCCEEDED
PROCESSING → FAILED
```

There is no `PaymentStatus.UNKNOWN`.

### Architecture Constraints

- Cross-module merchant reference is an ID, not a Merchant entity.
- No arbitrary public status/amount setters.
- Do not use Lombok `@Data`.
- Do not generate public setters for protected domain state.
- Business transition rules belong in the aggregate.
- Keep domain independent from persistence infrastructure.

### API / Persistence Changes

API:

- N/A.

Persistence:

- N/A.

### Flow / State / Transaction

```text
create
  ↓
CREATED
  ↓ startProcessing
PROCESSING
  ├── markSucceeded → SUCCEEDED
  └── markFailed    → FAILED
```

### Tests

Valid:

- New payment is `CREATED`.
- `CREATED → PROCESSING`.
- `PROCESSING → SUCCEEDED`.
- `PROCESSING → FAILED`.

Reject:

- `PROCESSING → PROCESSING`.
- `SUCCEEDED → PROCESSING`.
- `FAILED → PROCESSING`.
- `CREATED → SUCCEEDED`.
- `CREATED → FAILED`.
- `SUCCEEDED → FAILED`.
- `FAILED → SUCCEEDED`.

Tests must not load Spring.

### Non-goals

- Do not implement orchestration.
- Do not call providers.
- Do not implement persistence.
- Do not implement Refund workflows.

### Done

- PaymentIntent protects the frozen state machine.
- Invalid transitions cannot be performed through public behavior.
- Domain tests pass.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T05 — PaymentTransaction Aggregate

### Goal

Represent one provider attempt independently from PaymentIntent.

### Scope

Implement:

- `PaymentTransaction`.
- Creation/rehydration model.
- Terminal-state behavior.
- Domain tests.

### Dependencies

- P2-T03.

### Requirements

Conceptual state:

```text
internalId
publicId
paymentIntentId
attemptNo
provider
providerTransactionId
status
failureCode
failureMessage
startedAt
completedAt
version
```

Phase 2 confirmation creates:

```text
attemptNo = 1
status = PROCESSING
```

Required behavior:

```text
markSucceeded(...)
markFailed(...)
markUnknown(...)
```

Allowed transitions:

```text
PROCESSING → SUCCEEDED
PROCESSING → FAILED
PROCESSING → UNKNOWN
```

### Architecture Constraints

- PaymentTransaction remains a separate aggregate.
- Do not model it as a mutable giant collection inside PaymentIntent.
- No public state setters.
- Do not use Lombok `@Data`.
- Domain behavior must remain independent from Spring/JPA.

### API / Persistence Changes

API:

- N/A.

Persistence:

- N/A.

### Flow / State / Transaction

```text
PROCESSING
   ├── SUCCESS → SUCCEEDED
   ├── FAILURE → FAILED
   └── UNKNOWN → UNKNOWN
```

### Tests

- `PROCESSING → SUCCEEDED`.
- `PROCESSING → FAILED`.
- `PROCESSING → UNKNOWN`.
- `SUCCEEDED → FAILED` rejected.
- `FAILED → SUCCEEDED` rejected.
- `UNKNOWN → SUCCEEDED` rejected in Phase 2.
- Invalid attempt number is rejected where applicable.

### Non-goals

- Do not implement UNKNOWN reconciliation.
- Do not implement retry attempts.
- Do not implement persistence.
- Do not implement provider adapters.

### Done

- Transaction state rules are protected.
- UNKNOWN is correctly modeled.
- Domain tests pass.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T06 — Payment Persistence Adapters

### Goal

Persist and rehydrate PaymentIntent and PaymentTransaction behind domain
repository ports.

### Scope

Implement:

```text
PaymentIntentRepository
PaymentTransactionRepository

PaymentIntentEntity
PaymentTransactionEntity

PaymentIntentJpaRepository
PaymentTransactionJpaRepository

PaymentIntentRepositoryAdapter
PaymentTransactionRepositoryAdapter

persistence mapping
```

### Dependencies

- P2-T02.
- P2-T04.
- P2-T05.

### Requirements

PaymentIntent repository supports at least:

```text
save
findByPublicIdAndMerchantId
searchByMerchant
```

PaymentTransaction repository supports at least:

```text
save
findByPaymentIntentId
findLatestByPaymentIntentId
```

Persistence must preserve:

- Money.
- status.
- public IDs.
- internal relationships.
- version.
- timestamps.

### Architecture Constraints

- Application/domain depend on repository ports.
- Spring Data remains infrastructure-only.
- JPA entities remain Payment infrastructure-only.
- No JPA entity is exposed to HTTP or public module APIs.
- No cross-module JPA associations.
- Use `@Version` where specified.
- Do not use Lombok `@Data` on JPA entities.
- MapStruct is allowed for mechanical mapping.
- Prefer explicit mapping for rich aggregate rehydration if generated mapping
  would hide invariants.

### API / Persistence Changes

API:

- N/A.

Persistence:

- Map the schema introduced by P2-T02.
- No new migration unless an approved defect is discovered.

### Flow / State / Transaction

N/A.

### Tests

Using PostgreSQL Testcontainers:

- Save/reload PaymentIntent.
- Save/reload PaymentTransaction.
- Money mapping is correct.
- Status mapping is correct.
- Public-ID lookup works.
- Merchant ownership lookup works.
- Transaction ordering works.
- Optimistic version works.
- Entities do not leak through repository ports.

### Non-goals

- Do not implement create/confirm workflows.
- Do not implement providers.
- Do not change Merchant persistence.
- Do not add Phase 3+ schema.

### Done

- Both aggregates persist and rehydrate correctly.
- Domain/application has no Spring Data dependency.
- PostgreSQL integration tests pass.
- ArchUnit remains green.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T07 — Merchant Ownership Integration

### Goal

Centralize authenticated merchant resolution and Payment resource ownership.

### Scope

Implement:

- Payment-side merchant resolution.
- Ownership-aware Payment lookup.
- Cross-merchant not-found behavior.
- Required application helpers.

### Dependencies

- P2-T01.
- P2-T06.

### Requirements

Authenticated merchant comes from:

```text
MerchantApiPrincipal.merchantPublicId
```

Resolve through:

```text
MerchantAccessApi
```

Prefer ownership-aware query:

```text
findByPublicIdAndMerchantId(
    paymentPublicId,
    merchantInternalId
)
```

Cross-merchant access returns:

```http
404 Not Found
```

### Architecture Constraints

- Payment must not inject Merchant repository/entity.
- Merchant resolution uses Merchant public API.
- Client-provided merchant IDs cannot choose ownership.
- Internal merchant IDs must not reach HTTP responses.

### API / Persistence Changes

API:

- N/A.

Persistence:

- Repository queries may be added.
- No migration.

### Flow / State / Transaction

```text
API Key
  ↓
MerchantApiPrincipal
  ↓
MerchantAccessApi
  ↓
merchantInternalId
  ↓
merchant-scoped Payment lookup
```

### Tests

- Own payment lookup succeeds.
- Cross-merchant lookup fails as not found.
- ACTIVE merchant succeeds.
- SUSPENDED merchant is rejected.
- CLOSED merchant is rejected.
- Payment has no Merchant persistence dependency.

### Non-goals

- Do not implement create-payment HTTP API.
- Do not implement confirmation.
- Do not implement Idempotency.

### Done

- All Payment ownership resolution is merchant-scoped.
- Cross-merchant enumeration is prevented.
- Required tests pass.
- ArchUnit remains green.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T08 — Create PaymentIntent Use Case

### Goal

Implement PaymentIntent creation at the application layer.

### Scope

Implement:

```text
CreatePaymentIntentCommand
CreatePaymentIntentResult
CreatePaymentIntentService
```

### Dependencies

- P2-T04.
- P2-T06.
- P2-T07.

### Requirements

Input contains:

```text
authenticated merchant context
amountMinor
currency
orderId
description
```

There is no client-controlled `merchantId`.

Flow:

1. Resolve active merchant.
2. Validate/create Money.
3. Generate `pi_...` public ID.
4. Create PaymentIntent.
5. Persist it.
6. Return immutable result.

Initial state:

```text
CREATED
refundedAmount = 0
refundReservedAmount = 0
```

`orderId` is not an idempotency key and is not unique.

### Architecture Constraints

- `@Transactional` belongs at application use-case boundary.
- No external provider call.
- No Merchant persistence dependency.
- No JPA entity exposure.
- No internal ID exposure.
- No Idempotency implementation.

### API / Persistence Changes

API:

- Do not expose `POST /api/v1/payment-intents`.

Persistence:

- Insert PaymentIntent using existing schema.

### Flow / State / Transaction

```text
merchant context
      ↓
resolve ACTIVE merchant
      ↓
validate Money
      ↓
PaymentIntent.create()
      ↓
save
      ↓
COMMIT
      ↓
CREATED
```

### Tests

- Valid creation succeeds.
- `amount <= 0` rejected.
- Invalid currency rejected.
- Inactive merchant rejected.
- Public ID has `pi_` prefix.
- Ownership is persisted correctly.
- Duplicate orderId is allowed.
- Internal IDs do not leak.

### Non-goals

- Do not implement Idempotency.
- Do not expose create-payment HTTP endpoint.
- Do not call provider.
- Do not publish events.

### Done

- CreatePaymentIntent works at application level.
- Payment is persisted as `CREATED`.
- Required tests pass.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T09 — Payment Query & Read APIs

### Goal

Expose merchant-scoped Payment read APIs.

### Scope

Implement:

- Payment detail query.
- Payment list query.
- Filtering.
- Pagination.
- Transaction-history query.
- GET controllers and DTOs.
- API-key security integration.

### Dependencies

- P2-T06.
- P2-T07.

### Requirements

Expose:

```http
GET /api/v1/payment-intents
GET /api/v1/payment-intents/{paymentId}
GET /api/v1/payment-intents/{paymentId}/transactions
```

Authentication:

```text
Merchant API Key
```

Filters:

```text
status
orderId
createdFrom
createdTo
page
size
```

Defaults:

```text
page = 0
size = 20
sort = createdAt DESC
```

Maximum:

```text
size = 100
```

Payment responses may expose:

- Public ID.
- Order ID.
- Description.
- Amount.
- Currency.
- Status.
- Refunded amount.
- Refundable amount.
- Created/updated timestamps.

Transaction responses may expose safe normalized values:

- Public ID.
- Attempt number.
- Provider.
- Provider transaction ID.
- Status.
- Failure code.
- Failure message.
- Started/completed timestamps.

### Architecture Constraints

- Controllers remain thin.
- Controllers must not inject repositories.
- Merchant ownership is server-derived.
- Cross-merchant resources return 404.
- Do not expose:
  - internal IDs.
  - JPA version.
  - entities.
  - API-key hashes/secrets.
  - raw provider payloads.
- Failure messages must be normalized/safe.

### API / Persistence Changes

API:

- Add the three GET endpoints.

Persistence:

- Add query operations required by approved filters.
- No migration unless required by a reviewed defect.

### Flow / State / Transaction

```text
API Key
  ↓
MerchantApiPrincipal
  ↓
merchant-scoped query
  ↓
public DTO
```

### Tests

- Detail succeeds.
- Not found behavior.
- Cross-merchant returns 404.
- List works.
- Status filter works.
- OrderId filter works.
- Date filtering works.
- Pagination works.
- Max page size enforced.
- Default sorting works.
- Transaction history works.
- Invalid API key → 401.
- Revoked API key → 401.
- Internal fields do not leak.

### Non-goals

- Do not expose create/confirm POST endpoints.
- Do not implement Idempotency.
- Do not implement Refund.
- Do not call provider.

### Done

- All three read endpoints work.
- Merchant isolation is enforced.
- Response/error envelope matches API conventions.
- Tests pass.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T10 — PaymentProviderPort + Simulator

### Goal

Create a stable provider abstraction and deterministic simulator.

### Scope

Implement:

- `PaymentProviderPort`.
- `PaymentProviderRequest`.
- `PaymentProviderResult`.
- Provider identifier/type as needed.
- `SimulatorPaymentProvider`.
- Simulator configuration.
- Tests.

### Dependencies

- P2-T03.

### Requirements

Port concept:

```java
public interface PaymentProviderPort {

    PaymentProviderResult charge(
            PaymentProviderRequest request
    );
}
```

Request contains provider-relevant data:

```text
payment public reference
amount
currency
```

Normalized result contains:

```text
provider
outcome
providerTransactionId
failureCode
failureMessage
```

Simulator supports:

```text
SUCCESS
DECLINED
UNKNOWN
TECHNICAL_FAILURE
```

Suggested configuration:

```yaml
flowpay:
  payment:
    provider: simulator
    simulator:
      default-outcome: SUCCESS
```

### Architecture Constraints

- Domain/application must not know RestClient/provider HTTP details.
- Do not add simulator controls to public Payment APIs.
- Do not return provider-native response objects.
- Provider adapter must not mutate Payment aggregates.
- Failure metadata must be normalized.

### API / Persistence Changes

API:

- N/A.

Persistence:

- N/A.

### Flow / State / Transaction

```text
Payment application
      ↓
PaymentProviderPort
      ↓
SimulatorPaymentProvider
      ↓
PaymentProviderResult
```

### Tests

- SUCCESS result.
- DECLINED result.
- UNKNOWN result.
- TECHNICAL_FAILURE result.
- Normalized provider metadata is correct.
- Provider infrastructure does not leak into domain types.

### Non-goals

- Do not implement a real provider.
- Do not implement retry.
- Do not add Resilience4j.
- Do not finalize Payment state.

### Done

- Stable provider port exists.
- Simulator produces all four outcomes deterministically.
- Tests pass.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T11 — Prepare Confirmation Transaction

### Goal

Implement TX1 so PaymentIntent and PaymentTransaction are committed as
`PROCESSING` before the provider is contacted.

### Scope

Implement:

- `PreparePaymentConfirmationService`.
- Immutable preparation result.
- Required repository operations.
- Confirmation concurrency protection.

### Dependencies

- P2-T04.
- P2-T05.
- P2-T06.
- P2-T07.

### Requirements

The use case must:

1. Resolve active merchant.
2. Load merchant-scoped PaymentIntent.
3. Require `CREATED`.
4. Transition PaymentIntent → `PROCESSING`.
5. Create PaymentTransaction:
   - `attemptNo = 1`.
   - `status = PROCESSING`.
6. Persist both.
7. Commit before returning.

Return only immutable provider-call data such as:

```text
payment public ID
transaction public ID
amount
currency
provider
```

Two concurrent preparation attempts must result in only one successful
preparation.

### Architecture Constraints

- Run inside an application-level transaction.
- Do not call `PaymentProviderPort`.
- Do not return mutable aggregates outside the transaction.
- Use optimistic locking plus DB uniqueness unless another reviewed locking
  strategy is chosen.
- Translate persistence concurrency exceptions into application/domain errors.
- Payment must not access Merchant persistence directly.

### API / Persistence Changes

API:

- N/A.

Persistence:

- No new tables.
- Add repository locking/query operations if needed.
- No migration unless required by a reviewed defect.

### Flow / State / Transaction

```text
load PaymentIntent
        ↓
require CREATED
        ↓
PaymentIntent → PROCESSING
        ↓
create PaymentTransaction
attemptNo = 1
status = PROCESSING
        ↓
save both
        ↓
COMMIT
```

### Tests

- CREATED succeeds.
- PROCESSING rejected.
- SUCCEEDED rejected.
- FAILED rejected.
- Two concurrent preparations → only one success.
- Exactly one attempt #1 exists.
- Provider is not called.

### Non-goals

- Do not call provider.
- Do not finalize result.
- Do not implement Idempotency.
- Do not expose confirm HTTP API.
- Do not implement Outbox/RabbitMQ/Ledger/Refund/Webhook.

### Done

- PaymentIntent is committed `PROCESSING`.
- PaymentTransaction is committed `PROCESSING`.
- Duplicate concurrent preparation is prevented.
- Tests pass.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T12 — Finalize Confirmation Transaction

### Goal

Implement TX2 that atomically applies the normalized provider result.

### Scope

Implement:

- `FinalizePaymentConfirmationService`.
- Provider-outcome mapping.
- Required reload/update operations.
- Finalization tests.

### Dependencies

- P2-T04.
- P2-T05.
- P2-T06.
- P2-T10.

### Requirements

Reload PaymentIntent and PaymentTransaction from persistence.

Do not reuse stale mutable aggregates from TX1.

#### SUCCESS

```text
PaymentTransaction PROCESSING → SUCCEEDED
PaymentIntent      PROCESSING → SUCCEEDED
```

#### DECLINED

```text
PaymentTransaction PROCESSING → FAILED
PaymentIntent      PROCESSING → FAILED
```

#### UNKNOWN

```text
PaymentTransaction PROCESSING → UNKNOWN
PaymentIntent remains PROCESSING
```

#### TECHNICAL_FAILURE

```text
PaymentTransaction PROCESSING → FAILED
PaymentIntent      PROCESSING → FAILED
```

Changes to PaymentIntent and PaymentTransaction commit atomically.

### Architecture Constraints

- Application-level transaction.
- Reload latest state.
- No RabbitMQ publishing.
- No Outbox event yet.
- No Ledger/Webhook invocation.
- No raw provider payload in domain state.
- Invalid terminal transaction state must be rejected.

### API / Persistence Changes

API:

- N/A.

Persistence:

- Update existing Payment rows.
- No migration.

### Flow / State / Transaction

```text
reload Payment + Transaction
          ↓
validate PROCESSING
          ↓
map provider outcome
          ↓
mutate aggregates
          ↓
save
          ↓
COMMIT
```

### Tests

- SUCCESS atomic.
- DECLINED atomic.
- UNKNOWN keeps Payment PROCESSING.
- TECHNICAL_FAILURE marks both FAILED.
- Invalid transaction state rejected.
- Missing data handled safely.
- Safe normalized metadata persists.
- Raw provider response is not persisted.

### Non-goals

- Do not orchestrate provider call.
- Do not expose confirm endpoint.
- Do not implement Idempotency.
- Do not publish events.
- Do not reconcile UNKNOWN transactions.

### Done

- All four outcomes follow Frozen Decisions.
- Finalization is atomic.
- Tests pass.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T13 — Confirm Payment Orchestration

### Goal

Compose TX1 → provider → TX2 while proving that no database transaction is
active during the external provider call.

### Scope

Implement:

- `ConfirmPaymentService`.
- Preparation/provider/finalization orchestration.
- Transaction-boundary tests.
- Provider invocation-count tests.

### Dependencies

- P2-T10.
- P2-T11.
- P2-T12.

### Requirements

Execute:

```text
prepareConfirmation()
        ↓
TX1 COMMIT
        ↓
PaymentProviderPort.charge()
        ↓
NO DATABASE TRANSACTION
        ↓
finalizeConfirmation()
        ↓
TX2 COMMIT
```

Provider is called only after successful preparation.

If preparation fails, provider must not be called.

### Architecture Constraints

- Do not wrap the entire orchestration in one `@Transactional`.
- Do not rely on same-bean transactional self-invocation.
- Preferred structure:

```text
ConfirmPaymentService
    ├── PreparePaymentConfirmationService
    ├── PaymentProviderPort
    └── FinalizePaymentConfirmationService
```

- Exactly one provider call per successful preparation.
- No Outbox/RabbitMQ/Ledger/Webhook behavior.

### API / Persistence Changes

API:

- Do not expose confirm POST endpoint.

Persistence:

- N/A.

### Flow / State / Transaction

```text
TX1
PaymentIntent       PROCESSING
PaymentTransaction  PROCESSING
COMMIT

        ↓

Provider call
NO ACTIVE DB TRANSACTION

        ↓

TX2
apply provider outcome
COMMIT
```

### Tests

- SUCCESS orchestration.
- DECLINED orchestration.
- UNKNOWN orchestration.
- TECHNICAL_FAILURE orchestration.
- Provider called exactly once after successful preparation.
- Provider not called when preparation fails.

Inside fake provider:

```java
TransactionSynchronizationManager
    .isActualTransactionActive()
```

must return:

```text
false
```

A stronger test should query PostgreSQL while provider is running and confirm:

```text
PaymentIntent       = PROCESSING
PaymentTransaction  = PROCESSING
```

is already committed.

### Non-goals

- Do not expose confirm POST API.
- Do not implement Idempotency.
- Do not retry provider.
- Do not publish events.
- Do not implement reconciliation.

### Done

- TX1 → provider without TX → TX2 is proven.
- Provider invocation count is correct.
- Transaction-boundary tests pass.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T14 — Integration, Security & Concurrency Tests

### Goal

Verify the complete Phase 2 against real PostgreSQL and Phase 1 security.

### Scope

Add/complete:

- Migration integration tests.
- Application integration tests.
- Security tests.
- Read API tests.
- Transaction-boundary tests.
- Concurrency tests.
- Architecture tests.
- Information-leakage tests.

### Dependencies

- P2-T01 through P2-T13.

### Requirements

Use:

- PostgreSQL Testcontainers.
- Flyway.
- Spring Boot integration tests where appropriate.
- MockMvc/Spring Security tests for HTTP APIs.

Do not use H2.

Required scenarios:

#### Migration

```text
Phase 0/1 migrations → Payment migration
```

from a clean database.

#### Create

```text
valid             → CREATED
amount <= 0       → rejected
invalid currency  → rejected
inactive merchant → rejected
```

#### Payment state

```text
CREATED → PROCESSING → SUCCEEDED
CREATED → PROCESSING → FAILED
CREATED → PROCESSING + Transaction UNKNOWN
```

#### Invalid confirmation

```text
SUCCEEDED  → reject
FAILED     → reject
PROCESSING → reject
```

#### Transaction boundary

Prove:

```text
TX1 committed
↓
provider called without DB transaction
↓
TX2 committed
```

#### Concurrency

```text
two concurrent preparations
→ one success
→ one attempt #1
```

#### Security

```text
Merchant A → own payment        → success
Merchant A → Merchant B payment → 404
invalid API key                 → 401
revoked API key                 → 401
suspended merchant              → reject
```

#### Read API

Verify:

- detail.
- list.
- status filter.
- orderId filter.
- date filters.
- pagination.
- max page size.
- default sort.
- transaction history.

#### Information leakage

Do not expose:

- Database IDs.
- JPA versions.
- Merchant internal IDs.
- JPA entities.
- Raw API keys.
- API-key hashes.
- Raw provider payloads.
- Persistence exception details.

### Architecture Constraints

ArchUnit must enforce at least:

```text
payment -X-> merchant.infrastructure
payment -X-> MerchantEntity
payment -X-> MerchantJpaRepository
payment.domain -X-> payment.infrastructure
```

Controllers must not inject repositories.

All Phase 1 architecture rules remain active.

### API / Persistence Changes

API:

- N/A beyond already approved Phase 2 GET endpoints.

Persistence:

- N/A unless fixing a reviewed defect.

### Flow / State / Transaction

N/A.

### Tests

This task is the consolidated automated verification of all scenarios above.

### Non-goals

- Do not add Phase 3 behavior for test convenience.
- Do not expose write endpoints.
- Do not use H2.
- Do not weaken architecture rules.

### Done

- All required integration/security/concurrency tests pass.
- Phase 1 tests still pass.
- ArchUnit passes.
- No information-leakage regression exists.
- `./mvnw clean verify` passes.

Do not continue to the next task.

---

## P2-T15 — Quality Gate & Documentation

### Goal

Freeze Payment Core before beginning Phase 3 Idempotency.

### Scope

Perform:

- Clean-environment verification.
- Documentation review.
- Acceptance review.
- Final quality gate.

### Dependencies

- P2-T14.

### Requirements

Run:

```bash
docker compose down -v
docker compose up -d

./mvnw clean verify
```

Review/update when necessary:

```text
docs/product-requirements.md
docs/business-rules.md
docs/domain-model.md
docs/architecture.md
docs/database-design.md
docs/api-contract.md
docs/task-roadmap.md
docs/phase-2-payment-core.md
```

If implementation and specification disagree:

```text
STOP
 ↓
review decision
 ↓
fix implementation
OR
approve specification change
 ↓
update docs intentionally
```

### Architecture Constraints

- Do not modify docs merely to legitimize incorrect code.
- Do not begin Phase 3 while any Phase 2 acceptance item fails.
- Do not silently expand scope during final cleanup.

### API / Persistence Changes

API:

- N/A.

Persistence:

- N/A unless fixing an explicitly reviewed defect.

### Flow / State / Transaction

N/A.

### Tests

- Clean migration passes.
- Full suite passes.
- Security tests pass.
- Transaction-boundary tests pass.
- Concurrency tests pass.
- ArchUnit passes.
- `./mvnw clean verify` passes.

### Non-goals

- Do not implement Idempotency.
- Do not expose Payment write endpoints.
- Do not begin Refund/Ledger/Outbox/Webhook.

### Done

- Phase 2 Acceptance Checklist is fully satisfied.
- Documentation matches reviewed implementation.
- Clean quality gate passes.
- Phase 2 is marked `DONE/FROZEN`.

Do not continue to Phase 3 automatically.

---

## 8. Phase Acceptance Checklist

```text
[x] Phase 1 Exit Gate still passes.

[x] MerchantAccessApi exists.
[x] MerchantApiPrincipal provides merchant public ID.
[x] Payment does not access Merchant repositories/entities directly.
[x] Suspended/closed merchants are rejected.

[x] Payment migration applies from clean PostgreSQL.
[x] payment_intents schema is correct.
[x] payment_transactions schema is correct.
[x] Database constraints and indexes are verified.
[x] Payment uses internal BIGINT relationships.
[x] HTTP responses expose public IDs only.

[x] Money uses integer minor units.
[x] Currency is explicit.
[x] No float/double financial amount is used.

[x] PaymentIntent is a protected aggregate.
[x] PaymentTransaction is a separate aggregate.
[x] No public setter arbitrarily mutates payment state.

[x] New PaymentIntent starts CREATED.
[x] Only CREATED can start confirmation.
[x] PROCESSING can become SUCCEEDED.
[x] PROCESSING can become FAILED.
[x] UNKNOWN keeps PaymentIntent PROCESSING.
[x] UNKNOWN sets PaymentTransaction UNKNOWN.
[x] FAILED is terminal in Phase 2.

[x] CreatePaymentIntent application use case works.
[x] CreatePaymentIntent HTTP POST is not public yet.

[x] PaymentProviderPort exists.
[x] Simulator exists.
[x] SUCCESS works.
[x] DECLINED works.
[x] UNKNOWN works.
[x] TECHNICAL_FAILURE works.

[x] TX1 commits PROCESSING before provider call.
[x] Provider runs without active DB transaction.
[x] TX2 atomically finalizes Payment + Transaction.
[x] Provider is called exactly once per successful preparation.
[x] Provider is not called when preparation fails.

[x] Concurrent confirmation cannot create two attempt #1 transactions.
[x] Persistence concurrency exceptions do not leak publicly.

[x] GET payment detail works.
[x] GET payment list works.
[x] GET transaction history works.
[x] Pagination/filtering/sorting work.
[x] Cross-merchant access returns 404.

[x] Invalid API key fails authentication.
[x] Revoked API key fails authentication.

[x] No Idempotency implementation exists yet.
[x] No Payment write HTTP endpoint is public yet.
[x] No Refund implementation exists.
[x] No Ledger implementation exists.
[x] No Outbox business implementation exists.
[x] No RabbitMQ business publishing exists.
[x] No Webhook implementation exists.

[x] PostgreSQL Testcontainers pass.
[x] Security/integration tests pass.
[x] Transaction-boundary tests pass.
[x] Concurrency tests pass.
[x] ArchUnit passes.
[x] ./mvnw clean verify passes.
```

---

## 9. Definition of Done

Phase 2 is complete only when the application layer supports:

```text
CreatePaymentIntent
        ↓
      CREATED
```

and:

```text
ConfirmPayment
       ↓

TX1
PaymentIntent       PROCESSING
PaymentTransaction  PROCESSING
       ↓
COMMIT

       ↓

PaymentProviderPort
NO DATABASE TRANSACTION

       ↓

TX2
SUCCESS / DECLINED / UNKNOWN / TECHNICAL_FAILURE
       ↓
COMMIT
```

Public HTTP at the end of Phase 2:

```http
GET /api/v1/payment-intents
GET /api/v1/payment-intents/{id}
GET /api/v1/payment-intents/{id}/transactions
```

Application implementations exist but remain non-public:

```http
POST /api/v1/payment-intents
POST /api/v1/payment-intents/{id}/confirm
```

Phase 3 adds Idempotency before exposing them.

All Phase 1 functionality must remain operational.

---

## 10. Implementation Order

For the one-task-at-a-time Codex workflow, use numeric order:

```text
P2-T01
   ↓
P2-T02
   ↓
P2-T03
   ↓
P2-T04
   ↓
P2-T05
   ↓
P2-T06
   ↓
P2-T07
   ↓
P2-T08
   ↓
P2-T09
   ↓
P2-T10
   ↓
P2-T11
   ↓
P2-T12
   ↓
P2-T13
   ↓
P2-T14
   ↓
P2-T15
```

The `Depends on` column is the authoritative dependency graph.

Some tasks are technically parallelizable. For example, P2-T10 only depends
on P2-T03. However, when using one Codex task at a time, follow the numeric
order above for easier review and lower integration risk.

Working rules:

- One task = one Codex assignment.
- Review the diff and tests before starting the next task.
- Do not silently implement later tasks.
- Do not weaken tests or architecture constraints to make a task pass.
- Keep P2-T11, P2-T12, and P2-T13 separate.
- Transaction boundaries are a core engineering objective of Phase 2.
