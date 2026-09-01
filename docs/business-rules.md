# FlowPay — Business Rules

Rule identifiers are stable references for code reviews, tests, and future implementation tasks.

## 1. Identity and authentication

### BR-AUTH-001 — Email normalization

User emails must be trimmed and normalized to lowercase before lookup and persistence.

`" Viet@Example.COM "` becomes `viet@example.com`.

The database must also enforce case-insensitive uniqueness.

### BR-AUTH-002 — Password storage

Raw passwords must never be persisted or logged.

Passwords are hashed with Spring Security `PasswordEncoder` using BCrypt.

### BR-AUTH-003 — Registration atomicity

Registration creates:

- User.
- Merchant.
- `OWNER` membership.

All three changes must commit in one database transaction. If any step fails, the entire registration rolls back.

### BR-AUTH-004 — Initial user status

MVP Phase 1 does not implement email verification.

A successfully registered user starts as:

`ACTIVE`

Supported statuses:

- `ACTIVE`
- `LOCKED`
- `DISABLED`

### BR-AUTH-005 — Invalid credential privacy

Unknown email and wrong password must return the same external error:

`INVALID_CREDENTIALS`

The API must not reveal whether an account exists.

### BR-AUTH-006 — Access token

Dashboard access tokens are JWTs signed asymmetrically with RSA.

Default access-token TTL:

`15 minutes`

JWT subject is the user's public ID.

JWT must not contain internal database IDs or secrets.

### BR-AUTH-007 — Refresh token

Refresh tokens are opaque, cryptographically random values.

Default refresh-token TTL:

`7 days`

The raw refresh token is stored only in an HttpOnly cookie and is never persisted.

### BR-AUTH-008 — Refresh-token hashing

The database stores a SHA-256 digest of the refresh token, not the raw token.

High-entropy generated tokens do not use a password-style slow hash.

### BR-AUTH-009 — Refresh-token rotation

Every successful refresh invalidates the current refresh token and issues a replacement.

A refresh token is single-use for rotation purposes.

Concurrent refresh attempts must not result in multiple valid replacement chains.

### BR-AUTH-010 — Logout

Logout revokes the presented refresh token and clears the refresh-token cookie.

### BR-AUTH-011 — Refresh cookie

Cookie name:

`flowpay_refresh`

Required policy:

- `HttpOnly=true`
- `SameSite=Lax`
- `Path=/api/v1/auth`
- `Secure=true` in production
- local development may configure `Secure=false`

The raw refresh token must not appear in JSON responses.

## 2. Merchant

### BR-MER-001 — Merchant isolation

A merchant may only access resources owned by that merchant.

For cross-merchant resource lookup, the public API should normally return `404` rather than revealing the existence of the resource.

### BR-MER-002 — Initial membership

Registration creates exactly one initial membership:

`OWNER`

Prepared roles:

- `OWNER`
- `ADMIN`
- `DEVELOPER`
- `FINANCE`
- `VIEWER`

Member invitation and role-management features are outside Phase 1.

### BR-MER-003 — Merchant lifecycle

Merchant status values:

- `ACTIVE`
- `SUSPENDED`
- `CLOSED`

A suspended or closed merchant must not authenticate integration requests using API keys.

## 3. API keys

### BR-KEY-001 — API-key format

MVP test API keys use:

`fp_test_<cryptographically-random-secret>`

### BR-KEY-002 — Raw API key shown once

The raw API key may only be returned in the create-key response.

List/read operations must never return the raw key.

### BR-KEY-003 — API-key storage

Raw API keys must never be persisted.

The database stores:

- `key_prefix`
- `key_hash`

The hash is SHA-256 of the complete raw key.

### BR-KEY-004 — API-key lookup

Authentication first uses a non-secret key prefix to locate a small candidate set, then verifies the complete digest.

### BR-KEY-005 — Revocation

API-key lifecycle:

`ACTIVE -> REVOKED`

Revocation updates state; it does not delete the database record.

A revoked key cannot authenticate.

### BR-KEY-006 — Secret logging

API keys must never be written to application logs, traces, error payloads, or audit messages.

## 4. Money

### BR-MONEY-001 — Representation

Financial amounts are represented as integer minor units using `long`/`BIGINT`.

Do not use `float` or `double`.

Examples:

- `500000 VND -> 500000`
- `10.50 USD -> 1050`

### BR-MONEY-002 — Currency

Currency is an ISO-4217 three-letter code.

A `Money` value contains both:

- amount minor units
- currency

### BR-MONEY-003 — Positive financial operation

Payment and refund amounts must be greater than zero.

## 5. Payment

### BR-PAY-001 — Payment ownership

Every payment belongs to exactly one merchant.

Merchant identity is derived from authenticated server-side context, never accepted as a client-controlled `merchantId`.

### BR-PAY-002 — Immutable core value

Payment amount and currency cannot be changed after the payment intent is created.

### BR-PAY-003 — State machine

PaymentIntent states:

- `CREATED`
- `PROCESSING`
- `SUCCEEDED`
- `FAILED`
- `PARTIALLY_REFUNDED`
- `REFUNDED`

State transitions must be performed through domain behavior, not arbitrary setters.

### BR-PAY-004 — Confirm state

A `SUCCEEDED`, `PARTIALLY_REFUNDED`, or `REFUNDED` payment cannot be confirmed again.

Invalid state transitions return a business conflict.

### BR-PAY-005 — Provider decline versus HTTP failure

A provider decline is a payment result, not a FlowPay transport/server failure.

FlowPay can return HTTP `200` with payment status `FAILED`.

### BR-PAY-006 — Unknown provider outcome

A provider timeout or lost response is not automatically `FAILED`.

When the provider outcome is unknown:

- `PaymentTransaction = UNKNOWN`
- `PaymentIntent = PROCESSING`

A later inquiry/reconciliation step may resolve the final state.

## 6. Payment transactions

### BR-PTXN-001 — Separate provider attempts

A `PaymentTransaction` represents one provider interaction/attempt and is separate from the `PaymentIntent` aggregate.

### BR-PTXN-002 — Attempt uniqueness

`(payment_intent_id, attempt_no)` is unique.

### BR-PTXN-003 — Provider state

PaymentTransaction states:

- `PENDING`
- `PROCESSING`
- `SUCCEEDED`
- `FAILED`
- `UNKNOWN`

## 7. Idempotency

### BR-IDEM-001 — Required commands

Financial creation/command endpoints that can create duplicate financial effects must require `Idempotency-Key`.

MVP examples:

- create payment intent
- confirm payment
- create refund

### BR-IDEM-002 — Scope

Uniqueness scope:

`merchant + operation + idempotency key`

The same textual key may be used by another merchant or for another operation.

### BR-IDEM-003 — Same key, same request

A repeated equivalent request returns the same logical result and must not create a second financial operation.

### BR-IDEM-004 — Same key, different request

Reusing the same scoped idempotency key with a different request fingerprint returns:

`409 IDEMPOTENCY_KEY_REUSED`

### BR-IDEM-005 — Concurrent insertion

Correctness must rely on a database uniqueness constraint, not a vulnerable `exists -> insert` check.

### BR-IDEM-006 — In-progress duplicate

A concurrent equivalent request that finds the scoped key still processing must
not execute the financial operation again and returns:

`409 IDEMPOTENCY_REQUEST_IN_PROGRESS`

### BR-IDEM-007 — Completed response replay

A completed replay returns the stored original logical HTTP status and public
response body. Create-payment replay also preserves `Location`; all completed
replays return `Idempotency-Replayed: true`.

Replay must not rebuild its semantics from current aggregate state or invoke the
payment provider again.

### BR-IDEM-008 — Retention and cleanup

The default replay retention is 24 hours and is configurable. Cleanup deletes
only expired `COMPLETED` records in bounded batches; it must not blindly delete
`PROCESSING` records.

## 8. Refund

### BR-REF-001 — Refundable payment state

Refunds are allowed only from:

- `SUCCEEDED`
- `PARTIALLY_REFUNDED`

### BR-REF-002 — Refund currency

Refund currency is derived from the payment and cannot be supplied independently by the client.

### BR-REF-003 — Refund total invariant

At all times:

`refunded_amount + refund_reserved_amount <= payment_amount`

This invariant must remain true under concurrent requests.

### BR-REF-004 — Reservation

A refund reserves its amount before external processing.

On success:

- reserved decreases
- refunded increases

On failure:

- reserved decreases
- refunded does not increase

### BR-REF-005 — Concurrency ownership

Because the refund-total invariant belongs to `PaymentIntent`, the Payment module owns the locking/concurrency mechanism protecting it.

Refund-capacity decisions use a short pessimistic Payment row lock. The lock is
released before provider I/O; optimistic versions and the PostgreSQL refund
check remain additional protection.

### BR-REF-006 — Provider outcomes

Provider `SUCCESS` consumes the reservation and increases refunded amount.
Known `DECLINED` or `TECHNICAL_FAILURE` outcomes release the reservation and
produce a terminal failed Refund.

An `UNKNOWN` or otherwise ambiguous outcome does not release capacity and does
not mark the Refund failed. Refund remains `PROCESSING` until a future approved
reconciliation mechanism resolves it.

### BR-REF-007 — Refund idempotency

Refund creation uses operation `REFUND_CREATE` with scope:

`merchant + operation + idempotency key`

The semantic fingerprint contains the Payment public ID, amount in minor units,
and one normalized optional reason. Completed replay preserves the original
status, body, and `Location` and never reserves again, creates another Refund,
or invokes the provider.

### BR-REF-008 — Refund reason

Reason is optional, trimmed once, blank-normalized to `null`, and limited to 255
characters. The same normalized value is used by fingerprinting, the aggregate,
provider request, and public response.

## 9. Ledger

### BR-LED-001 — Double-entry

Every posted ledger transaction contains debit and credit entries.

### BR-LED-002 — Balance invariant

For one ledger transaction:

`sum(DEBIT) == sum(CREDIT)`

An unbalanced transaction must not be persisted.

### BR-LED-003 — Append-only posting

Completed ledger transactions and entries are not updated or deleted for business corrections.

Corrections are represented by reversal postings.

### BR-LED-004 — Duplicate posting protection

A business event may be delivered more than once.

Ledger posting must be idempotent for a business reference.

## 10. Outbox and integration events

### BR-EVT-001 — Reliable event persistence

A business state change and its required integration event must be persisted atomically using the transactional outbox pattern.

### BR-EVT-002 — Broker isolation

Business modules must not directly depend on `RabbitTemplate` or broker-specific APIs.

### BR-EVT-003 — Stable integration contracts

Cross-module/broker events use explicit versioned payloads.

Do not serialize JPA entities or domain aggregates directly.

### BR-EVT-004 — At-least-once

Consumers must assume at-least-once delivery and protect against duplicate processing.

## 11. Webhooks

### BR-WEB-001 — Delivery semantics

Webhook delivery is at-least-once.

A merchant may receive the same event multiple times.

### BR-WEB-002 — Event identifier

Every webhook event has a unique public event ID for merchant-side deduplication.

### BR-WEB-003 — Signature

Webhook payloads are signed using HMAC-SHA256.

Signed input:

`timestamp + "." + rawRequestBody`

Header format:

`FlowPay-Signature: t=<timestamp>,v1=<signature>`

### BR-WEB-004 — Replay tolerance

Webhook signature verification should reject timestamps outside a five-minute tolerance unless explicitly configured otherwise.

### BR-WEB-005 — Secret storage

Webhook secrets must be recoverable for signing, so they are encrypted at rest rather than one-way hashed.

Production should eventually use a secret-management/KMS solution.

### BR-WEB-006 — Retry

Failed webhook deliveries are retried using backoff and eventually transition to `DEAD` after the configured maximum attempts.

## 12. Security and logging

### BR-SEC-001

Never expose or log:

- raw password
- password hash
- raw refresh token
- refresh-token hash
- raw API key except its one-time creation response
- API-key hash
- JWT signing private key
- webhook secret
- webhook encrypted secret
- Authorization header
- internal database IDs

### BR-SEC-002

Unexpected exceptions must be converted to the public Problem Details contract without stack traces, SQL, Hibernate details, or database constraint names.
