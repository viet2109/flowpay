# FlowPay — Domain Model

## 1. Architectural style

FlowPay begins as a modular monolith with explicit domain/module boundaries.

Main modules:

- `identity`
- `merchant`
- `payment`
- `idempotency`
- `refund`
- `ledger`
- `webhook`

Shared technical capabilities live under `common` and top-level `infrastructure`.

Aggregates reference aggregates in other modules by ID, not by cross-module JPA object relationships.

## 2. Identity module

### User — Aggregate Root

Purpose:

Represents a human dashboard identity.

Core fields:

- internal ID
- public ID (`usr_...`)
- normalized email
- password hash
- first name
- last name
- status
- version
- timestamps

Status:

- `ACTIVE`
- `LOCKED`
- `DISABLED`

Important behavior:

- lock
- disable
- state validation

Public setters must not allow arbitrary status mutation.

### RefreshToken — Identity/Security Aggregate

Purpose:

Represents one server-side refresh-token session record.

The domain/persistence record contains:

- internal ID
- user internal ID
- SHA-256 token digest
- expiry
- revoked timestamp
- replacement token reference
- created/last-used timestamps

The raw token is not part of the persisted aggregate.

Lifecycle:

```text
ACTIVE
  |
  | refresh/logout/expiry
  v
REVOKED / EXPIRED
```

Rotation creates a new token record and invalidates the old record atomically.

## 3. Merchant module

### Merchant — Aggregate Root

Core fields:

- internal ID
- public ID (`mrc_...`)
- name
- status
- version
- timestamps

Status:

- `ACTIVE`
- `SUSPENDED`
- `CLOSED`

### MerchantMember — Separate persistence/domain object

Represents membership between a user and merchant.

Fields:

- merchant internal ID
- user internal ID
- role
- created timestamp

Roles:

- `OWNER`
- `ADMIN`
- `DEVELOPER`
- `FINANCE`
- `VIEWER`

MVP registration creates only `OWNER`.

A Merchant does not hold a JPA `@OneToMany` collection of User entities.

### ApiKey — Aggregate Root

Purpose:

Authenticates merchant backend integration requests.

Fields:

- internal ID
- public ID (`key_...`)
- merchant internal ID
- display name
- key prefix
- SHA-256 key digest
- status
- last-used timestamp
- optional expiry
- revoked timestamp
- created timestamp

Status:

- `ACTIVE`
- `REVOKED`

Behavior:

- revoke
- verify active state
- record last use

Raw API-key secret exists only at generation time and is not persisted.

## 4. Payment module

### PaymentIntent — Aggregate Root

Represents the merchant's intent to collect a fixed amount in a fixed currency.

Fields:

- internal ID
- public ID (`pi_...`)
- merchant internal ID
- merchant order ID
- description
- original Money
- status
- refunded amount
- reserved refund amount
- version
- timestamps

Status:

- `CREATED`
- `PROCESSING`
- `SUCCEEDED`
- `FAILED`
- `PARTIALLY_REFUNDED`
- `REFUNDED`

Key behavior:

- start processing
- mark succeeded
- mark failed
- reserve refund
- complete refund
- release refund

The aggregate owns the invariant:

`refunded + reserved <= original amount`

### PaymentTransaction — Aggregate Root

Represents one interaction/attempt against a payment provider.

Fields:

- internal ID
- public ID (`ptxn_...`)
- payment-intent internal ID
- attempt number
- provider
- provider transaction ID
- status
- failure code/message
- started/completed timestamps
- version

Status:

- `PENDING`
- `PROCESSING`
- `SUCCEEDED`
- `FAILED`
- `UNKNOWN`

PaymentTransaction is not a child collection inside PaymentIntent.

## 5. Idempotency module

### IdempotencyRecord — Aggregate Root

Represents ownership and the replay snapshot for one scoped financial command.

Core fields:

- internal ID
- merchant internal ID
- operation
- case-sensitive idempotency key
- semantic request hash
- status
- optional resource type and public ID pair
- original HTTP status and public response payload
- created, completed, and expiry timestamps

Phase 3 operations:

- `PAYMENT_INTENT_CREATE`
- `PAYMENT_INTENT_CONFIRM`

Active status lifecycle:

```text
PROCESSING -> COMPLETED
```

The persistence schema reserves `FAILED` for future explicitly approved
recovery semantics, but the Phase 3 domain does not produce it.

Key behavior:

- start a new scoped execution
- reserve an optional public resource identity
- complete exactly once with the original response snapshot
- reject completion that does not match the reserved resource
- expose replay data only after completion

The aggregate belongs to the Idempotency module. It references Merchant by
internal ID and a business resource only by type and public ID; it has no
cross-module JPA association and does not depend on Payment persistence.

## 6. Refund module

### Refund — Aggregate Root

Fields:

- internal ID
- public ID (`re_...`)
- merchant internal ID
- payment-intent internal ID
- Money
- status
- reason
- provider refund ID
- version
- timestamps

Status:

- `CREATED`
- `PROCESSING`
- `SUCCEEDED`
- `FAILED`

Refund does not mutate PaymentIntent directly.

It calls a public Payment module API to reserve, complete, or release refund capacity.

## 7. Ledger module

### LedgerAccount — Aggregate Root

Represents an account used by the financial ledger.

Examples:

- system clearing
- merchant payable
- FlowPay fee revenue
- refund clearing

Fields:

- internal ID
- public ID (`la_...`)
- account code
- account type
- owner type/id
- currency
- status

### LedgerTransaction — Aggregate Root

Represents one atomic balanced posting.

Fields:

- internal ID
- public ID (`ltxn_...`)
- posting type
- business reference type/id
- currency
- description
- occurred/created timestamps
- entries

### LedgerEntry — Child Entity

Belongs to exactly one LedgerTransaction.

Fields:

- internal ID
- ledger transaction ID
- ledger account ID
- entry number
- direction
- amount

Direction:

- `DEBIT`
- `CREDIT`

A LedgerEntry is not an aggregate root and is not independently mutated.

## 8. Webhook module

### WebhookEndpoint — Aggregate Root

Represents merchant webhook configuration.

Fields:

- internal ID
- public ID (`wep_...`)
- merchant ID
- URL
- encrypted secret
- status
- version
- timestamps
- subscribed event types

Status:

- `ACTIVE`
- `DISABLED`

### WebhookEvent — Aggregate Root

Immutable public event payload.

Fields:

- internal ID
- public ID (`evt_...`)
- event type
- resource type/id
- JSON payload
- occurred/created timestamps

### WebhookDelivery — Aggregate Root

Represents delivery of one webhook event to one endpoint.

Fields:

- event ID
- endpoint ID
- status
- attempt count
- next-attempt timestamp
- delivered timestamp
- last HTTP status/error
- version
- timestamps

Status:

- `PENDING`
- `DELIVERING`
- `DELIVERED`
- `RETRYING`
- `DEAD`

### WebhookDeliveryAttempt — Child/history record

Records one concrete delivery attempt.

It is append-only diagnostic history.

## 9. Cross-module relationships

Conceptual relationships:

```text
User
  |
  v
MerchantMember ---> Merchant ---> ApiKey
                        | \
                        |  +----> IdempotencyRecord
                        v
                  PaymentIntent
                    /       \
                   v         v
        PaymentTransaction  Refund

Payment/Refund
      |
      | integration events
      +------------+
      |            |
      v            v
   Ledger       Webhook
```

Rules:

- Cross-module relation is represented by IDs.
- No cross-module JPA `@ManyToOne`, `@OneToMany`, or `@OneToOne`.
- A module must not import another module's persistence entity/repository.
- Cross-module synchronous reads/commands use an explicit public application API.
- Cross-module side effects preferably use integration events.

## 10. Public module APIs

Examples:

### MerchantOnboardingApi

Used by registration orchestration.

Responsibilities:

- create merchant
- create initial `OWNER` membership
- return a small onboarding result

It does not expose MerchantEntity or repository types.

### MerchantAccessApi

Used by authenticated integration modules, beginning with Payment, to resolve the
current merchant without importing Merchant persistence types.

Responsibilities:

- resolve by merchant public ID
- require the merchant to be `ACTIVE`
- return an immutable `ActiveMerchantSnapshot` containing only the internal ID
  needed for ownership persistence and the merchant public ID

The internal ID is an in-process module contract value. It must not be exposed
through HTTP responses.

### PaymentQueryApi

May expose immutable `PaymentSnapshot` for Refund decisions.

### PaymentRefundApi

Owns refund-capacity mutations on PaymentIntent:

- reserve refund
- complete refund
- release refund

### Idempotency application contracts

Used by Payment write orchestration to acquire a scoped execution, complete and
replay a stored public response, or safely release a Confirm reservation before
provider invocation. Decisions are explicit (`NEW`, `REPLAY`, `IN_PROGRESS`,
`KEY_REUSED`) and do not expose Idempotency persistence entities.

## 11. Value objects

### Money

`Money` contains:

- `long amountMinor`
- ISO currency

Expected operations:

- add
- subtract
- compare
- verify same currency

Money-related business code should prefer this value object over passing unrelated primitive amount/currency values.

## 12. Domain versus persistence

Rich financial aggregates such as PaymentIntent and LedgerTransaction should remain independent of persistence concerns.

Persistence mapping must not bypass domain invariants when creating a new aggregate.

Rehydration from trusted persisted state may use explicit rehydration factories.

MapStruct is appropriate for mechanical DTO mapping, but mapping that encodes domain reconstruction rules should remain explicit when clarity would otherwise be lost.
