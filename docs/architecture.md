# FlowPay — Architecture

## 1. Architectural approach

FlowPay MVP is a modular monolith.

Each business module follows a DDD-inspired layered structure:

```text
<module>/
├── api/
├── application/
├── domain/
└── infrastructure/
```

Do not create empty packages only to satisfy this diagram. Create packages when implementation needs them.

## 2. Dependency direction

Within a module:

```text
API
 |
 v
Application
 |
 v
Domain

Infrastructure
   |
   +----> Application/Domain ports
```

Rules:

- Domain must not depend on infrastructure.
- API must not access persistence repositories directly.
- Application services orchestrate use cases.
- Infrastructure implements ports and technical details.

## 3. Top-level packages

```text
com.flowpay
├── common
├── infrastructure
├── identity
├── merchant
├── payment
├── idempotency
├── refund
├── ledger
└── webhook
```

### `common`

Contains genuinely reusable non-business primitives such as:

- API response envelope.
- Problem Details support.
- correlation/request identifiers.
- Money value object when shared.
- public ID primitives.

`common` is not a dumping ground and should not contain generic business services.

### top-level `infrastructure`

Contains cross-cutting technical capabilities:

- security plumbing.
- request correlation/filtering.
- messaging/outbox relay infrastructure.
- observability.
- configuration.

Business rules do not belong here.

## 4. Module boundaries

A module must not:

- import another module's persistence entity.
- inject another module's Spring Data repository.
- define cross-module JPA entity associations.
- return JPA entities from its public API.
- mutate another module's aggregate directly.

Allowed cross-module communication:

1. Public application API for synchronous business decisions.
2. Integration events for asynchronous side effects.
3. IDs for persistence references.

Database foreign keys may still enforce referential integrity because MVP modules share one PostgreSQL database.

## 5. Transaction boundaries

`@Transactional` belongs at application use-case boundaries.

Do not place transaction ownership in controllers or domain objects.

### Registration transaction

Phase 1 registration is one database transaction:

```text
RegistrationService
   |
   +--> create User
   |
   +--> MerchantOnboardingApi
          |
          +--> create Merchant
          +--> create OWNER membership
   |
 COMMIT
```

Any failure rolls back User, Merchant, and membership together.

This is allowed because the modular monolith shares one database, while repository ownership remains inside the appropriate module.

### External provider call

Do not keep a database transaction open across an external HTTP call.

Payment confirmation is conceptually:

```text
TX 1
- PaymentIntent -> PROCESSING
- create PaymentTransaction
COMMIT

NO DATABASE TX
- call provider

TX 2
- finalize PaymentTransaction
- finalize PaymentIntent
- create outbox event (Phase 6 onward)
COMMIT
```

At the Phase 2 freeze, TX 2 contains only PaymentTransaction and PaymentIntent
finalization. Transactional outbox persistence and event publication remain
explicitly deferred to Phase 6.

### Idempotent payment commands

Idempotency is a dedicated business module. It owns its domain model,
repository port, persistence adapter, acquisition decisions, response snapshots,
retention, and cleanup. It does not belong in `common`, top-level
`infrastructure`, or Payment infrastructure.

Payment may call only Idempotency public/application contracts. Idempotency must
not access Payment entities, repositories, or infrastructure. The database may
still enforce `idempotency_records.merchant_id -> merchants.id` because the
modular monolith shares one PostgreSQL database.

Create PaymentIntent has no external call, so its idempotent orchestration is
atomic:

```text
ONE DATABASE TX
- acquire PROCESSING Idempotency record
- create PaymentIntent
- persist the original public response snapshot
- complete Idempotency record
COMMIT
```

Confirm Payment preserves the Phase 2 provider boundary:

```text
Payment ownership/state preflight (no Idempotency row yet)

Idempotency reservation TX
- acquire PROCESSING record
COMMIT

Payment TX 1
- PaymentIntent -> PROCESSING
- create PaymentTransaction
COMMIT

NO DATABASE TX
- call provider

Payment TX 2
- finalize PaymentTransaction
- finalize PaymentIntent
COMMIT

Idempotency completion TX
- persist the original public response snapshot
- mark COMPLETED
COMMIT
```

The Payment preflight prevents cross-merchant or already-invalid requests from
creating an Idempotency reservation. Payment TX 1 must still revalidate ownership
and state to protect against races.

A PROCESSING Confirm reservation may be removed only when the execution owner
can prove that Payment TX 1 failed and the provider was never invoked. From the
point provider invocation begins, an unexpected failure is uncertain: retain the
PROCESSING record and forbid automatic retry or release.

After Payment TX 2, the completion transaction stores a stable public snapshot
containing payment and transaction public IDs/statuses plus normalized provider,
provider transaction ID, failure code, and failure message. `SUCCEEDED`,
`DECLINED`, and `TECHNICAL_FAILURE` preserve HTTP status 200; `UNKNOWN` preserves
202. Replay decodes this stored snapshot and never reconstructs it from current
Payment state or a raw provider response.

The public create and confirm adapters both require merchant API-key
authentication and an `Idempotency-Key`. Confirm is exposed only through
`POST /api/v1/payment-intents/{paymentId}/confirm`; its controller maps the
authenticated principal, path ID, and parsed key into the idempotent application
command and never accesses Payment or Idempotency repositories directly.

Idempotency retention defaults to 24 hours and is configurable. A new
PROCESSING record expires relative to creation time; successful completion
resets expiration relative to completion time so the full replay window is
preserved. Cleanup runs on a configurable schedule and deletes only expired
COMPLETED records through bounded PostgreSQL batches. Each run also has a
configured batch limit, and PROCESSING records remain untouched even when their
provisional expiration is in the past.

### Refund transaction and concurrency boundaries

Refund is a dedicated module. It owns the Refund aggregate, persistence,
provider port/simulator, orchestration, and HTTP APIs. PaymentIntent remains the
sole owner of refund capacity and the invariant:

```text
refundedAmount + refundReservedAmount <= paymentAmount
```

Refund may call only `MerchantAccessApi`, `PaymentRefundApi`, and Idempotency
application contracts. It never accesses Merchant, Payment, or Idempotency
entities/repositories/infrastructure. `PaymentRefundApi` returns immutable
snapshots and performs an explicit successful-charge lookup; a latest
PaymentTransaction is not assumed to be the successful provider operation.

Refund processing uses four boundaries:

```text
PREPARE TX
- acquire REFUND_CREATE Idempotency ownership
- lock merchant-owned PaymentIntent
- validate and reserve refund capacity
- create Refund PROCESSING
COMMIT

NO DATABASE TX / NO ROW LOCK
- invoke RefundProviderPort

FINALIZE TX
- lock merchant-owned Refund
- lock PaymentIntent
- SUCCESS: consume reservation and mark Refund SUCCEEDED
- known failure: release reservation and mark Refund FAILED
- UNKNOWN: retain reservation and Refund PROCESSING
COMMIT

IDEMPOTENCY COMPLETION TX
- store REFUND resource identity and original public response snapshot
COMMIT
```

The fixed finalization lock order is Refund row then Payment row. Both rows use
pessimistic write locking only in short local transactions; existing optimistic
versions and the PostgreSQL Payment refund-total check remain secondary/final
guards. No lock or transaction crosses provider I/O.

The existing normalized provider outcome enum belongs to Payment domain, so
Refund defines an equivalent Refund-owned outcome contract rather than creating
a Refund-to-Payment-domain dependency merely to reuse a type. Unexpected or
ambiguous provider failures preserve Refund `PROCESSING`, the Payment
reservation, and Idempotency `PROCESSING`; retry/reconciliation is deferred.

## 6. Identity and dashboard authentication

Dashboard users authenticate with JWT access tokens.

### Access token

- JWT.
- RSA asymmetric signature.
- default TTL 15 minutes.
- `sub` = user public ID.
- claims include active merchant public ID and role for MVP.

Example:

```json
{
  "sub": "usr_01K...",
  "merchant": "mrc_01K...",
  "role": "OWNER"
}
```

Do not place internal IDs or secrets in the token.

### Refresh token

Refresh tokens are opaque random values stored in an HttpOnly cookie.

The database stores only a SHA-256 digest.

Refresh uses rotation:

```text
old refresh token
      |
      v
validate + atomically consume
      |
      +--> revoke old record
      +--> create replacement
      +--> issue new access token
```

Concurrent refresh attempts must not create multiple valid replacement chains.

Tests may generate ephemeral RSA key pairs. Production key material is injected using secret configuration and is never committed.

## 7. Merchant integration authentication

Merchant server-to-server APIs authenticate using:

`Authorization: Bearer fp_test_...`

This is distinct from dashboard JWT authentication.

Recommended security organization is path-based security chains/entry points rather than treating JWT and API keys as an undifferentiated credential.

Conceptually:

```text
/auth/**                 public/auth flows
/merchant/**             dashboard JWT
/payment-intents/**      merchant API key
/refunds/**              merchant API key where applicable
```

Exact path rules follow the API contract.

## 8. Principal abstraction

Application/business code must not read `SecurityContextHolder` directly throughout the codebase.

Security infrastructure resolves authenticated identities into explicit principal objects.

Recommended principal types:

### DashboardPrincipal

Contains:

- user public ID
- active merchant public ID
- merchant role

### MerchantApiPrincipal

Contains:

- merchant public ID
- API-key public ID

A small current-principal provider may expose the relevant principal to application code.

Do not force dashboard and API-key authentication into one ambiguous business identity.

## 9. Password and secret hashing

Different secret types use different strategies.

### Human passwords

Use BCrypt via Spring Security `PasswordEncoder`.

Reason: human passwords have relatively low entropy and need a deliberately expensive password hash.

### Generated refresh tokens and API keys

Use cryptographically strong random generation and store SHA-256 digests.

Reason: generated tokens already have high entropy and require efficient deterministic lookup/verification.

Raw generated secrets are not persisted.

## 10. API key architecture

Generation:

```text
SecureRandom
   |
   v
fp_test_<secret>
   |
   +--> returned once
   |
   +--> prefix stored
   +--> SHA-256 digest stored
```

Authentication:

```text
raw key
  |
  +--> validate format
  +--> extract prefix
  +--> load candidate key
  +--> calculate SHA-256 digest
  +--> constant-time digest comparison
  +--> verify key ACTIVE
  +--> verify merchant ACTIVE
  +--> MerchantApiPrincipal
```

`last_used_at` is operational metadata, not a financial correctness invariant.

## 11. Payment provider port

Application/domain code does not know `RestClient`, provider URLs, or provider-specific response codes.

Port concept:

```java
interface PaymentProviderPort {
    PaymentProviderResult charge(PaymentProviderRequest request);
}
```

Infrastructure adapters implement the port.

Provider-specific results are normalized into FlowPay meanings such as:

- success
- declined
- unknown
- technical failure

## 12. Events and outbox

This section describes the target architecture from Phase 6 onward. Through
Phase 4, FlowPay has no transactional outbox, RabbitMQ business-event publisher,
Ledger consumer, or Webhook consumer implementation.

Phase 5 uses one explicit transitional exception: successful Payment and Refund
finalization calls `LedgerPostingApi` synchronously through Ledger's application
boundary, and the Ledger posting joins the existing local PostgreSQL
finalization transaction. Payment and Refund must not depend on Ledger domain,
persistence, JPA, or infrastructure. Phase 6 replaces this direct trigger with
the transactional outbox and an at-least-once Ledger consumer while reusing the
same duplicate-safe posting API.

No provider call is moved into a database transaction. During the Phase 5
transitional path, Ledger persistence failure after an external provider
success rolled back the local financial finalization. After the Phase 6
cutover, source finalization and its required Outbox row commit atomically and
independently of Ledger consumption; a later Ledger failure follows bounded
consumer retry and dead-letter handling without rolling back the source.

Business modules do not publish RabbitMQ messages directly.

They publish explicit integration events through an abstraction that persists an outbox row in the same database transaction as the business state change.

Concept:

```text
Business transaction
    |
    +--> update aggregate
    +--> INSERT outbox event
    |
  COMMIT

Outbox relay
    |
    v
RabbitMQ
```

Integration events are versioned contracts and must not serialize JPA entities.

The frozen Phase 6 success pipeline uses `payment.succeeded.v1` and
`refund.succeeded.v1`. The source finalization transaction persists business
state and a `PENDING` Outbox row atomically, but never performs broker I/O. The
relay reads an immutable due-event snapshot in a short database transaction,
publishes without an active database transaction, and records `PUBLISHED` only
after a positive publisher confirm and successful routing. Publication failure
remains durably retryable with capped exponential backoff and deterministic
equal jitter between 50% and 100% of the current capped delay.

The broker envelope is stable JSON containing `eventId`, `eventType`,
`aggregateType`, `aggregateId`, `occurredAt`, and `payload`. The active V1
payloads are:

| Event type | Aggregate | Payload fields |
|---|---|---|
| `payment.succeeded.v1` | `PAYMENT_INTENT` / payment public ID | `merchantInternalId`, `paymentPublicId`, `amountMinor`, `currency`, `occurredAt` |
| `payment.processing.v1` | `PAYMENT_INTENT` / payment public ID | Same scalar fields as payment success V1 |
| `payment.failed.v1` | `PAYMENT_INTENT` / payment public ID | Payment identity/Money/time fields plus normalized `failureCode`, `failureMessage` |
| `refund.succeeded.v1` | `REFUND` / refund public ID | `merchantInternalId`, `refundPublicId`, `paymentPublicId`, `amountMinor`, `currency`, `occurredAt` |
| `refund.processing.v1` | `REFUND` / refund public ID | Same scalar fields as refund success V1 |
| `refund.failed.v1` | `REFUND` / refund public ID | Refund identity/Money/time fields plus normalized `failureCode`, `failureMessage` |

P7-T05 completes production source publication for all six types. Preparation
persists processing events in the source transaction; known terminal failures
persist failed events in finalization. UNKNOWN produces no terminal event.
Refund processing is NEW-only, and rejected/replayed operations do not emit.
The typed producer ports and Outbox writer require the caller transaction.
Success V1 schemas and Ledger's success-only bindings are unchanged. P7-T08
provisions the independent durable `flowpay.webhook.events` queue with six
explicit V1 bindings and `flowpay.webhook.events.dlq` via `webhook.dead` on the
existing DLX. Processing/failed events are now routable without changing Ledger.

Payloads contain only the explicit consumer facts above. Authentication and
idempotency values, credentials, persistence entities/versions, and raw provider
responses are not event data.

RabbitMQ delivery is at-least-once. A top-level transport listener validates
the explicit envelope and delegates to a transactional Ledger event handler,
which reuses the existing `LedgerPostingApi`. Ledger business-reference
uniqueness absorbs equivalent duplicate delivery. No generic exactly-once,
Inbox, distributed lock, or PostgreSQL/RabbitMQ XA boundary is introduced.

The Ledger listener applies bounded infrastructure retry with three total
attempts, exponential delays starting at 500 milliseconds, a multiplier of two,
and a five-second cap. After exhaustion it rejects without requeue so the
existing dead-letter exchange routes the message to
`flowpay.ledger.events.dlq`. Equivalent `ALREADY_POSTED` duplicates are
acknowledged immediately. Consumer failure never changes a source Outbox row
that the relay has already marked `PUBLISHED`; DLQ replay is an explicit future
operation rather than an automatic loop.

The Payment and Refund cutovers are complete. Their production success paths no
longer call Ledger directly; the verified Outbox writer, confirmed publisher,
relay, and Ledger consumer own eventual Ledger posting.

Phase 7 adds Webhook as a second, independent consumer of the same exchange. It
preserves the two success V1 contracts and adds explicit
`payment.processing.v1`, `payment.failed.v1`, `refund.processing.v1`, and
`refund.failed.v1` contracts. The Webhook consumer materializes each source
event exactly once in PostgreSQL by source event ID and creates zero or more
delivery rows from the ACTIVE subscription snapshot in the same short
transaction. An equivalent duplicate is acknowledged without recomputing
subscriptions; a contradictory duplicate fails closed and follows bounded
consumer retry/dead-letter handling.

P7-T08 implements this materialization boundary. The listener ACK follows a
successful application transaction. PostgreSQL `INSERT ... ON CONFLICT` on
`source_event_id` arbitrates concurrent duplicates, and normalized source facts
plus canonical public JSON values detect contradictions without comparing JSON
property order or whitespace. Matching ACTIVE endpoint rows are selected with
`FOR SHARE`; endpoint disable takes `FOR UPDATE`, serializing it against the
subscription snapshot. Event snapshots persist even without subscribers.
The public body retains the full source occurrence timestamp; database timestamp
columns use microsecond precision. No current Payment/Refund queries, merchant
HTTP calls, HMAC, or delivery retries occur in materialization.

Webhook consumer retry has separate additive `flowpay.messaging.webhook-consumer`
properties and mirrors Ledger's bounded defaults (three attempts, 500ms initial
interval, multiplier two, five-second cap). Exhausted failures reject without
requeue into the Webhook DLQ; consumer failure does not change Outbox publication
state. Provision the Webhook queue/bindings before, or atomically with, enabling
the four additional source publishers during deployment.

Outbound delivery uses three separate boundaries:

```text
materialize event/deliveries TX
→ claim delivery + open attempt TX
→ signed HTTP POST with no database transaction
→ fenced result-finalization TX
```

The claim increments `attemptCount`, stores a lease, and uses that attempt number
as a fencing token. Expired leases are recoverable and may produce an external
duplicate, which is part of the documented at-least-once contract. Endpoint
disable, claim, and materialization coordinate through database row locks; an
already in-flight request uses its immutable URL/secret/body snapshot and may
finish, while future claims use the latest ACTIVE endpoint configuration.

FlowPay guarantees stable identity and body per public Webhook event, but not
ordering across separate events for the same aggregate. Merchants deduplicate by
`FlowPay-Event-Id` and tolerate processing/terminal events arriving out of
order. Webhook delivery failure cannot roll back or mutate Payment, Refund,
Ledger, or an already published Outbox record.

## 13. Synchronous query versus asynchronous side effect

Use a synchronous module API only when a business operation needs another module's current state to make a decision.

Example:

`Refund -> PaymentRefundApi`

Use events for downstream side effects.

Example:

```text
payment.succeeded.v1
   |
   +--> Ledger
   +--> Webhook
```

Payment and Refund do not synchronously call Ledger or Webhook services inside
their finalization transactions. Webhook remains event-driven only, and the
temporary Phase 5 direct Ledger trigger has been removed.

## 14. Persistence

### IDs

Use:

- internal `BIGINT` primary keys for database relationships.
- prefixed ULID-based public IDs at API boundaries.

Examples:

- `usr_...`
- `mrc_...`
- `key_...`
- `pi_...`
- `ptxn_...`
- `re_...`
- `la_...`
- `ltxn_...`
- `wep_...`
- `evt_...`

### Schema

Flyway is the schema owner.

Hibernate uses:

`ddl-auto=validate`

Never `create`, `update`, or `create-drop` in normal application profiles.

## 15. Mapping

Lombok may reduce boilerplate but must not create uncontrolled domain mutability.

Do not use `@Data` on domain aggregates or JPA entities.

Do not generate public setters for protected domain state.

MapStruct is appropriate for repetitive mechanical mapping such as DTO-to-DTO or simple response mapping.

Prefer explicit mapping when:

- rehydrating a rich aggregate.
- mapping encodes business semantics.
- hidden generated mapping would make invariants unclear.

MapStruct mappers must not contain business rules.

## 16. Testing architecture

### Domain unit tests

No Spring context.

Examples:

- state transition rules.
- refund invariants.
- balanced ledger.

### Application tests

Test orchestration using mocked ports where useful.

### Integration tests

Use real PostgreSQL through Testcontainers.

Do not replace PostgreSQL with H2.

Phase 3 has a consolidated Spring Boot and MockMvc end-to-end suite backed by
PostgreSQL Testcontainers and Flyway. It uses real threads and latches to verify
Create/Confirm concurrency, merchant and operation scopes, stable replay
snapshots, and exactly-once provider execution across all normalized outcomes.

Phase 4 extends the PostgreSQL-backed verification with clean V007 migration,
Refund domain/persistence/API/security coverage, same-key Idempotency replay,
different-key over-refund concurrency, fixed lock ordering, provider calls
outside database transactions, and Payment/Phase 3 regression protection.

RabbitMQ integration tests use RabbitMQ Testcontainers when messaging is implemented.

### Architecture tests

ArchUnit should enforce critical package dependencies, including:

- domain does not depend on infrastructure.
- API does not access persistence.
- modules do not import another module's infrastructure.
- no forbidden cross-module repository/entity dependencies.

## 17. Logging and observability

Every HTTP request receives/propagates `X-Request-Id`.

Request ID is placed in MDC and returned in the response.

Do not log secrets.

Known authentication and Idempotency failures must not log supplied credentials,
Idempotency keys, request hashes, or internal identifiers. The unexpected HTTP
error fallback emits only a generic failure marker; it must not log exception
messages or stack traces that can contain SQL constraint names, persistence
details, or request values. Correlation remains available through the request ID
already carried in MDC.

Actuator and Micrometer provide health/metrics foundation.

OpenTelemetry support may be configured for tracing, but business-specific metrics/traces are added when corresponding features exist.

## 18. Dependency policy

Do not add technology only to increase stack size.

Current MVP foundation includes technologies justified by planned MVP features.

Potential later additions such as Redis, Kafka, Resilience4j, Spring Modulith, Spring Batch, ShedLock, QueryDSL, OpenSearch, or WebFlux require an explicit problem/use case before adoption.
