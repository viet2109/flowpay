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

P8-T06 stores optional source `traceparent` in the Outbox transaction (V012,
nullable VARCHAR(55)), separately from all six payloads and the JSON envelope.
Relay snapshots preserve it across retries/restarts; the publisher validates and
adds only a Rabbit `traceparent` header. Legacy/invalid metadata is absent, never
a business failure. No ambient relay-thread context is propagated or retained.
No baggage/tracestate/request IDs, source-domain tracing dependencies, new
transaction boundaries or changes to confirms/backoff/dedupe. T06 added no spans;
T07 adds bounded attempt spans with the explicit parent policy below.

P8-T07 wraps each relay publication and listener invocation with a fresh span
under the validated stored/transport source creation context. Publish/processing
attempts are siblings, not a mutable last-send parent. Listeners scope parsing
and application dispatch independently, close before broker backoff, propagate
the original exception and preserve AUTO ACK-after-commit/retry/DLQ behavior.
No ambient thread parent, baggage or tracestate is inherited. Missing/malformed/
non-string metadata starts an independent root, never rejects valid facts.

Webhook-owned V013 nullable `webhook_events.traceparent` stores only the first
successful materialization context; equal redelivery cannot replace it. A small
application tracing port is implemented in Webhook infrastructure, reading its
own technical context outside transactions. Each claimed delivery and expired
lease recovery has a distinct bounded span; existing HTTP CLIENT spans nest
under delivery spans. Attempts after restart remain siblings under first
materialization, not hours-long spans. Domain aggregates/public JSON/signatures/
merchant headers are unchanged. Guarded APPLIED/STALE observation is diagnostic,
not a business result. No links are exported (the zero-link limit is unchanged).
Shared trace helpers do not query repositories/DB. SDK/exporter failure cannot
repeat work or replace its result/failure. No new SDK, dependency, lock, financial
transaction boundary or retry/lease budget is introduced.

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

P7-T09 supplies the stateless `WebhookSigner`, immutable transient HTTP request,
normalized HTTP result, and dedicated JDK HTTP adapter behind `WebhookHttpClientPort`.
HMAC-SHA256 signs the ASCII Unix-seconds prefix plus `.` and the exact outbound
body bytes. The adapter uses the same bytes in its POST, revalidates the URL,
disables redirects, and rejects calls with an active database transaction.
Configurable `flowpay.webhook.http.connect-timeout` / `request-timeout` default
to two / five seconds and must be positive. It performs one client invocation,
not a retry loop; HTTP 2xx is success and every other final status is failure.
Transport/timeout/TLS/interruption diagnostics are fixed codes, never exception
messages. Request `toString()` redacts URL, body, and plaintext secret.
Response handling completes at headers and closes the body stream without
reading/draining it, so an oversized or indefinitely slow merchant response body
cannot extend an otherwise acknowledged attempt. No response body is retained.
P7-T10 supplies delivery claiming, persisted attempt finalization, and scheduling.

`WebhookDeliveryWorker` selects a bounded ACTIVE-endpoint candidate batch in a
short transaction that skips contended delivery/endpoint rows. Candidates are
not claims: each is rechecked and claimed in its own independent transaction
immediately before sending, so later items do not consume their lease waiting
behind earlier HTTP calls. Claim locks the endpoint for share first, then locks
the delivery `FOR UPDATE SKIP LOCKED`, increments the attempt, and atomically
inserts its OPEN history row. Finalization uses the same endpoint-before-delivery
lock order, checks DELIVERING plus expected attempt number, and commits the
delivery transition and one-time history completion together. Duplicate or
stale results are ignored without overwriting history.

The immutable send snapshot contains the persisted event body and current
endpoint URL/encrypted secret; decryption and UTF-8 encoding happen after claim
commit. Rotation/update/disable cannot change that in-flight snapshot. 2xx
finalizes DELIVERED; other outcomes delegate to `WebhookDeliveryFailurePolicy`.
Its initial implementation uses the approved five retry delays with deterministic
additive jitter keyed by public delivery ID and next attempt number; a sixth
failure, or a failure after endpoint disable, becomes DEAD. Transport diagnostics
are fixed codes only, and elapsed milliseconds are bounded to the persisted INT.
Persistence failure never triggers an inline resend; it leaves a lease for the
recovery flow to handle.

`flowpay.webhook.delivery-worker` defaults to enabled, one-second fixed delay,
100 candidates, and a 30-second lease. The lease must strictly exceed the HTTP
request timeout; durations and batch size must be positive. Focused worker tests
disable background scheduling and invoke batches explicitly; the P7-T13 E2E
suite enables the real scheduler with loopback-only receivers. `flowpay.webhook.delivery`
defaults to `retry-delays=10s,30s,2m,10m,1h` and `retry-jitter-max=0.20`.
P7-T11 completes expired-lease recovery and atomic endpoint-disable cancellation.
Before each send batch, the worker selects a bounded batch of expired DELIVERING
rows, including disabled endpoints, while skipping contended delivery/endpoint
locks. Each recovery has its own short transaction: lock endpoint before
delivery, recheck lease/status/expected attempt, close the OPEN attempt with
`DELIVERY_LEASE_EXPIRED`, and use the same failure policy to schedule RETRYING or
mark DEAD. It does not increment attempt count, send HTTP, or fabricate HTTP
status/duration for the abandoned worker. Those unknown history fields remain
NULL. A recovered attempt's late result cannot overwrite the recovered schedule
or later attempt. Failure to persist one recovery rolls it back and does not
prevent unrelated recovery or due delivery processing.

Endpoint disable holds the endpoint's exclusive row lock and invokes a MANDATORY
cancellation use case in that same transaction. Scheduled PENDING/RETRYING rows
are read in bounded pages with write locks (no SKIP LOCKED) and stopped through
domain behavior; endpoint and all pages commit or roll back together. History,
attempt counts, DELIVERING, DELIVERED, and existing DEAD rows are retained. A
currently DELIVERING request may finish successfully; failure or expiry after
disable becomes DEAD, never a new scheduled retry. Repeated disable is idempotent.
Diagnostics are normalized application codes and bounded to 512 UTF-16 code
units without splitting a surrogate pair; the storage bound is not a substitute
for secret sanitization.

P7-T12 exposes dashboard-JWT delivery list/detail and asynchronous manual retry.
Webhook-owned SQL projections join the persisted event and endpoint, requiring
both to belong to the authenticated merchant; bounded list queries avoid N+1
aggregate loading. Responses map only explicit public fields through MapStruct,
with attempt history ordered by attempt number and no source IDs, leases,
versions, secrets, ciphertext, or merchant response bodies.

Manual retry resolves an owned JDBC projection, then takes a shared endpoint
lock before a delivery write lock in one application transaction. The projection
does not populate JPA's persistence context, so the locked delivery is loaded
fresh after any competing retry commits. Locked state must still be DEAD with
an ACTIVE endpoint. Domain behavior schedules RETRYING immediately and keeps
counts/history; the API returns 202 without calling the worker or HTTP client.
Concurrent manual retries serialize and a loser receives WEBHOOK_INVALID_STATE.
The originally observed attempt count is also rechecked, so a stale retry cannot
schedule a newer DEAD attempt if the worker has already failed the winning retry.
Endpoint disable uses its exclusive lock to prevent retry from scheduling after
disable commits. Only the existing worker opens the next numbered attempt.

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

P7-T13 adds `PhaseSevenWebhookEndToEndIntegrationTest`: real source application
services invoked through merchant APIs, PostgreSQL/Flyway, the confirmed Outbox
relay, both RabbitMQ consumers, the enabled delivery scheduler, and the production
HTTP/signing adapters with a loopback-only receiver. Only external Payment/Refund
provider ports have controlled outcomes. Endpoints and secrets come from the
dashboard API; an independent HMAC implementation verifies the exact received
bytes, and the receiver checks that claim/OPEN history are already committed.

The suite covers all six public types, UNKNOWN without false terminal events,
subset/multiple/disabled endpoints, equivalent broker republication, retained
event identity/body, rotation on a future attempt, timeout retry, six failures
to DEAD, and dashboard manual retry to success. Ledger consumes successes in
parallel and retains balanced, duplicate-safe postings. HTTP assertions allow
at-least-once delivery and do not promise cross-event ordering or exactly-once
external processing. A test-only clock advances through the unchanged production
retry delays/jitter; bounded eventual assertions replace arbitrary sleeps.
Database cleanup precedes scheduler initialization, and listener/receiver cleanup
prevents active work from crossing test boundaries. No production configuration,
API, schema, event contract, or dependency changes are required.

P7-T14 adds `WebhookDeliveryHardeningIntegrationTest` with real PostgreSQL and
loopback HTTP but explicitly invoked workers. Bounded receiver barriers exercise
rotation/disable during an in-flight request and an old HTTP outcome arriving
after lease recovery and a later attempt's success. A result-persistence fault
after a merchant's successful acknowledgement proves that recovery can resend
the identical event ID/body without rewriting the abandoned attempt's unknown
HTTP outcome. Independent HMAC checks verify old/new secret snapshots; captured
logs and persisted diagnostics exclude secrets, ciphertext, signatures, and
injected sensitive exception text. Authenticated ciphertext tampering prevents
HTTP while unrelated deliveries still progress.

The materialization concurrency test verifies one source-event snapshot with
the complete matching endpoint set, rather than only one endpoint. RabbitMQ
verification distinguishes delivery DEAD from materializer DLQ and proves that
equivalent broker redelivery cannot revive DEAD. HTTP/URL tests retain bounded
timeouts and no redirects, and verify production URL rejection before signing
or network invocation. Existing claim/recovery, dashboard manual-retry races,
tenant isolation, and architecture tests remain part of the regression gate.
These tests do not add locks, weaken production URL policy, or promise exactly-once
external processing; DNS-rebinding/egress controls remain Phase 8 work.

### Architecture tests

ArchUnit should enforce critical package dependencies, including:

- domain does not depend on infrastructure.
- API does not access persistence.
- modules do not import another module's infrastructure.
- no forbidden cross-module repository/entity dependencies.

P7-T15 explicitly bans Payment/Refund/Ledger dependencies on Webhook, source
implementation access from Webhook, and messaging access to Webhook HTTP or
persistence implementations and aggregates. The existing typed materialization
boundary permits only its two public event/resource enum values in addition to
Webhook application contracts; it does not expose aggregates. Merchant reads
are limited to `MerchantAccessApi` / `ActiveMerchantSnapshot`. Controllers cannot
invoke delivery execution or cryptography ports, and materialization cannot
perform HTTP/signing. All earlier ArchUnit rules remain in place.

Transaction regressions resolve all five delivery persistence boundaries by
exact signature and require independent write-capable transactions; removing
or renaming a boundary can no longer silently skip an assertion. The real
Phase 7 E2E fixture also checks that successful Payment/Refund provider calls
have no active DB transaction. Its financial-isolation regression compares
complete Payment, Transaction, Refund, Ledger, and published Outbox snapshots
(including versions/timestamps/payloads) through delivery retries, DEAD, and
manual retry. Original Create/Confirm/Refund Idempotency responses replay without
another provider call or financial mutation. Prior UNKNOWN, refund-capacity,
success-only Ledger routing, tenant/security, and public API regressions remain
part of the full verification gate.

### Phase 7 quality gate

P7-T16 completed on 2026-10-04. Phase 7 is DONE/FROZEN and the functional
simulator MVP is complete; Phase 6 remains DONE/FROZEN. No new API, schema,
event contract, dependency, transaction boundary, or locking strategy was needed.
Applied V010/V011 migrations remain unchanged; later shared-schema corrections
require reviewed forward migrations.

Verification used JDK 21 and Maven 3.9.16: 59 focused Webhook migration tests,
then `clean verify` with 1,173 tests, zero failures/errors/skips. Optional OTLP
metrics export was disabled for this local run; no business worker, consumer,
or infrastructure test was excluded by run-specific overrides. No test-skip
flag was supplied.

| Acceptance area | Existing verification evidence |
|---|---|
| V001–V011, upgrade preservation, constraints/indexes | `WebhookConfigurationMigrationTest`, `WebhookDeliveryMigrationTest`, earlier migration suites |
| Endpoint lifecycle, JWT/tenant isolation, encrypted secret, URL policy | `WebhookEndpointApiTest`, cryptography and URL-policy suites |
| Six source events, unchanged success V1, snapshot/dedupe, independent DLQ | `SourceEventLifecycleIntegrationTest`, `WebhookIntegrationEventConsumerIntegrationTest`, `WebhookEventMaterializationIntegrationTest` |
| Exact-body HMAC, bounded no-redirect HTTP | `HmacSha256WebhookSignerTest`, `WebhookHttpClientIntegrationTest` |
| Claim/attempt/lease, stale fencing, jitter/DEAD, disable/rotation, manual retry/history | `WebhookDeliveryExecutionIntegrationTest`, `WebhookDeliveryRecoveryIntegrationTest`, `WebhookDeliveryHardeningIntegrationTest`, `WebhookDeliveryApiTest` |
| DB + Rabbit + HTTP, financial isolation and earlier phase regressions | `PhaseSevenWebhookEndToEndIntegrationTest`, `PhaseSixEndToEndIntegrationTest`, Payment/Refund/Idempotency/Ledger suites |
| Module boundaries and no external call inside DB transactions | `ArchitectureTest`, `TransactionBoundaryRegressionTest` |

The clean infrastructure smoke check used a disposable Compose project with
fresh isolated PostgreSQL 16.15/RabbitMQ 4.3 volumes and healthy services. The
checked-in Compose definition pins development container names; merely changing
`-p` would not isolate that definition. Development volumes were not reset.
Testcontainers separately provided fresh test databases/brokers, including
migration verification; Compose health alone does not verify schema/application
behavior. Only the disposable gate project's resources are eligible for cleanup.

The gate does not claim exactly-once HTTP, ordered delivery, real-money
processing, or production readiness. At that gate, Phase 8 network/operations/
security/load hardening had not started. Its subsequently assigned design and
task boundaries are recorded in `production-engineering.md`.

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

P8-T03 adds owning-application metrics for committed Payment/Refund normalized
outcomes, per-call provider duration and Idempotency decisions/reservations.
Outcome/reservation counters run only after the existing transaction commits;
diagnostic decisions include replay/conflict even when an outer TX rolls back.
UNKNOWN stays non-terminal; repeat Refund UNKNOWN observations use existing
persisted metadata/row locking to avoid inflating logical counts. Equivalent
HTTP replay invokes no provider or new logical outcome. No observation adds
locks, transactions, schema, financial states or event fields.

The common recorder contains technical recording/afterCommit failure isolation,
not repository queries or business orchestration. Domain aggregates remain free
of Micrometer/OpenTelemetry (ArchUnit enforced). Provider timing preserves the
original result/exception and runs outside DB transactions. Bounded enum-only
labels and fixed SLO buckets are frozen in `production-engineering.md`; there
are no identity, money, URL, credential, failure-message or payload labels.
Counters are best-effort process-local observations, not durable accounting;
registry/export failure cannot change financial results. Actual OTLP HTTP 503
and throwing-registry fixtures verify failure isolation.

P8-T04 adds Outbox-owned and Webhook-owned read-only projection ports/adapters.
Each region returns one aggregate SQL row; no cross-module repository/entity
access, payload scan/materialization, merchant endpoint or schema change. Owning
metrics components cache the result; shared technical cache/recording code has
no module/JDBC dependency (ArchUnit enforced). Scrapes never query the DB.
Thirty-second sampling is cadence-limited/single-flight, skips caller TXs and
uses a dedicated named two-thread scheduler without replacing business-worker
scheduling. Statement timeout is 2s; pool acquisition still uses its own timeout.

Shared-DB backlog/age gauges are replicated snapshots, not per-instance disjoint
counts: choose a fresh replica or max, never sum. Initial unavailable values are
NaN; failed sampling keeps the old snapshot with growing monotonic sample age
and a bounded failure observation. Query cost scales with backlog and replicas;
Java tests do not claim measured staging/load capacity.

Outbox transport attempts are distinct from conditional PUBLISHED-after-commit.
Ledger repository claim decisions separate equivalent duplicate/conflict/failure
from newly committed posting (including reversal). Webhook OPEN-claim, delivered
and expired-lease recovery counters follow commit/fencing; worker HTTP groups
remain attempt diagnostics when finalization fails or a late result is fenced.
Telemetry failures cannot mutate source/financial/delivery state. Existing
transaction boundaries, locking, retry/lease budgets, events and APIs are intact.
The exact scope/labels/units/aggregation limits are in production-engineering.md.

P8-T05 reuses the installed Boot Micrometer/OpenTelemetry bridge and its SDK,
servlet observations and batch processor. The server convention omits raw URL
attributes without changing HTTP metric tags; every configured exporter is
wrapped with a final bounded allow-list. Names/resource/scope are fixed, and
events/links/status descriptions/arbitrary attributes/tracestate are removed.
Incoming propagation reads W3C traceparent only, never baggage or B3. Root
sampling/export configuration and console MDC fields are explicit. Request IDs
remain independent of trace IDs and the public contract is unchanged.

Provider application calls and the dedicated Webhook HTTP transport have scoped
CLIENT spans outside DB transactions, with normalized outcomes only. Scope
cleanup restores context; telemetry failure preserves results/exceptions and
interruption. No context headers are added to signed Webhook HTTP. Domain code
remains telemetry-free. OTLP export is opt-in, asynchronous/bounded and can lose
telemetry during outage; third-party exporter diagnostics and unsafe request/SQL
DEBUG defaults are disabled. Policy and operational limits are documented in
production-engineering.md. No second SDK, Java dependency, schema, payload,
state, financial locking/transaction or authentication change is introduced.

P8-T06 implements durable Outbox source-context propagation and P8-T07 adds
bounded publication/consumer/worker spans as described above. P8-T08 adds the
isolated monitoring lab, versioned dashboards and operational alert/runbook
fixtures without changing application defaults, Java wiring or financial
semantics. Exact image digests, external-secret/private-listener boundaries,
cached-gauge freshness/max aggregation and retention limits are recorded in
production-engineering.md and monitoring-runbook.md. Monitoring outage is outside
the financial critical path; no external paging/HA, production image, TLS staging
or performance/recovery acceptance is implied by this lab.

## 18. Dependency policy

Do not add technology only to increase stack size.

Current MVP foundation includes technologies justified by planned MVP features.

Potential later additions such as Redis, Kafka, Resilience4j, Spring Modulith, Spring Batch, ShedLock, QueryDSL, OpenSearch, or WebFlux require an explicit problem/use case before adoption.

## 19. Phase 8 production-engineering design boundary

P8-T01 establishes the Linux/container staging design after the verified Phase 7
release was merged into remote main. It changes documentation only: no new
runtime library, schema, public API/event, state machine, locking or transaction
boundary is introduced. See `production-engineering.md` for the shared design
record and `task-roadmap.md` for the 18 task owners/dependencies.

Owning modules provide after-commit logical observations and bounded durable
projections; shared observability does not acquire cross-module repository
access. Diagnostic attempts/latency are distinct from committed outcomes.
Metrics/traces/logs exclude secrets/bodies and unbounded identity labels, and
collector/sampling failure cannot change financial outcomes.

P8-T06 implements validated nullable Outbox W3C metadata under reviewed V012,
not a change to success V1, source facts or public signed Webhook bodies.
P8-T07 adds reviewed V013 Webhook-only metadata and bounded asynchronous span relations.
Source/materialization/claim/HTTP/finalize transaction boundaries are preserved.

T02 implements a prod-only dedicated management listener (loopback:9091 by
default) with local-connector-port authorization. Only internal GET/HEAD health,
info and Prometheus are permitted; all business-listener Actuator routes are
denied, including for dashboard JWTs. Main-listener `/livez` checks process state;
`/readyz` checks readinessState plus DB, not RabbitMQ or telemetry. Broker outage
does not remove durable source + Outbox acceptance; aggregate health still
reflects dependency failure. Startup validates required production configuration
before bean binding, rejects dev/test conveniences, and loads matching persistent
RSA keys with sanitized failures. Bounded HTTP graceful shutdown is tested on
real listeners; image/worker lifecycle and private staging network enforcement
remain T12-T15 work. See section 6 of `production-engineering.md` for the exact
configuration and probe contract. No schema or business contract changed.

T09 implements effective-destination protection with a fixed enforcing CONNECT
proxy and an application container on an internal-only network. Only the proxy
joins its outbound network; trusted DB/broker/telemetry paths remain separate.
The proxy evaluates every cached IPv4/IPv6 destination and denies private,
special-use and mixed answers. A preliminary application DNS lookup alone is
not network-hardening acceptance. See [egress design and evidence](webhook-egress.md).

After reproduced proxied IPv6-literal SNI failures on Java 21/25, the user approved
Apache HttpClient 5 solely for the Webhook infrastructure adapter. Java 21 and
the public port/API/event/financial/transaction contracts remain unchanged.
Client 5.6.4/Core 5.4.4 are explicitly selected over Boot's managed pair for
documented stable async TLS/Core fixes. The dedicated asynchronous HTTP/1 client
uses a fixed proxy route with no system proxy or direct fallback, trusted TLS
chains and explicit hostname/IP-SAN verification. Only the operator's proxy DNS
is snapshotted at bounded startup; refresh it by restarting the client after
proxy address changes. Merchant DNS and effective-destination checks stay in
the proxy. Redirects, transport retries, cookies, auth and compression are off.

A monotonic delivery deadline covers signing, pool/CONNECT/TLS/header waits;
an independent headers future permits hard cancellation of unread bodies.
Plaintext CONNECT refusals are not merchant acknowledgements. No outgoing trace
headers, raw HTTP logs or active DB transaction are allowed. Final clean verify,
TLS/IP-SAN/DNS/rebinding/bypass fixtures and affected monitoring acceptance pass
on the same final JAR/Java 21 runtime. Artifact and separate focused/full/native
gate evidence are recorded in `webhook-egress.md`; this is T09 acceptance, not
release-image, staged-host-firewall or production-readiness evidence.
Security/API defects, new Java dependencies and concrete trace DDL require their
recorded reviews before implementation, not incidental changes during telemetry.

### P8-T10 reviewed auth and ingress protections

The user approved the concrete auth marker and NGINX per-IP budget/429 proposals
after the documented cookie CSRF and missing-ingress-limit findings. See
[API security review](api-security-review.md) for evidence, preconditions,
dependency/runtime selection, tests and remaining gates.

`AuthRequestMarkerFilter` is a chain-local, infrastructure-only filter before
bearer processing on the dashboard/auth chain, not a duplicate global servlet
filter. Decoded Security/MVC path matching includes context paths and encoded
letters. Auth POSTs need exactly one `X-FlowPay-CSRF: 1`; auth cross-origin
preflights are denied regardless of marker. Existing normalized `403 ACCESS_DENIED`
is used without credential/session/controller/DB work or Set-Cookie. Controllers
remain thin, body/state/token/TTL contracts stay unchanged, and bearer financial/
dashboard APIs are not forced into cookie CSRF semantics. No permissive CORS or
Origin/Host/Forwarded-derived trust is added. Same-origin UI and non-browser
auth clients must explicitly send the reviewed marker.

The pinned NGINX 1.30.5 policy has finite shared zones/workers/connections and
bounded waits, runs non-root/read-only/capability-free, and does not log request
data or retry upstream mutations. TCP peer address selects shared auth and
mutation buckets; inbound forwarded/rewrite headers are stripped and request IDs/
Host overwritten. Production application forwarded-header handling is `none`.
Excess receives a correlated safe `429 RATE_LIMIT_EXCEEDED` before reaching
source/Idempotency/provider work; upstream 429 is passed through. Budget values
and retry behavior are in `api-contract.md`. Replays consume ingress budget
without changing duplicate-safe financial semantics. No Redis or new module,
Java dependency, schema or financial locking/transaction change is introduced.

Isolated HTTP policy fixtures verify real TCP IPv4/IPv6 limits, spoofing,
non-forwarding, refill and actual prod/DB/broker/replay/Ledger behavior. They do
not publish backend/management ports or provision TLS staging. T13 must supply
reviewed TLS listen/ingress and prove no backend bypass on the selected host;
T11/T12 own scanners/image acceptance and T14/T15 own load/multi-instance quotas.

Staging, load, multi-instance failure and DB/broker/key restore evidence remain
separate from Java unit/integration tests. Performance and recovery targets are
declared before their respective acceptance runs on recorded artifacts/resources.
No external hosting/cloud provisioning, destructive dev reset, automatic DLQ
replay, PUBLISHED reset or provider reconciliation is authorized by this design.
