# FlowPay — Business Rules v1

## Payment

### BR-PAY-001 — Positive amount
A payment amount must be greater than zero.

### BR-PAY-002 — Immutable currency
Payment currency cannot be changed after PaymentIntent creation.

### BR-PAY-003 — No reconfirm after success
A `SUCCEEDED` payment cannot be confirmed again.

### BR-PAY-004 — Merchant isolation
A merchant can access only its own payments.

Cross-merchant resource access should be represented as `404 Not Found` to avoid leaking resource existence.

### BR-PAY-005 — Ambiguous provider outcome
A provider timeout or other ambiguous outcome must not automatically mark the PaymentIntent as `FAILED`.

Use:

```text
PaymentTransaction = UNKNOWN
PaymentIntent      = PROCESSING
```

until reconciliation/inquiry resolves the outcome.

## Payment State Machine

PaymentIntent states:

```text
CREATED
PROCESSING
SUCCEEDED
FAILED
PARTIALLY_REFUNDED
REFUNDED
```

Primary transitions:

```text
CREATED -> PROCESSING
PROCESSING -> SUCCEEDED
PROCESSING -> FAILED
SUCCEEDED -> PARTIALLY_REFUNDED
SUCCEEDED -> REFUNDED
PARTIALLY_REFUNDED -> PARTIALLY_REFUNDED
PARTIALLY_REFUNDED -> REFUNDED
```

Invalid state transitions must return a business conflict, not silently overwrite state.

PaymentTransaction states:

```text
PENDING
PROCESSING
SUCCEEDED
FAILED
UNKNOWN
```

## Refund

### BR-REF-001 — Eligible payment state
Refunds are allowed only when the payment is `SUCCEEDED` or `PARTIALLY_REFUNDED`.

### BR-REF-002 — Positive refund
Refund amount must be greater than zero.

### BR-REF-003 — Refund amount invariant
At all times:

```text
refundedAmount + reservedRefundAmount <= paymentAmount
```

### BR-REF-004 — Same currency
Refund currency is inherited from the payment and cannot differ from it.

### BR-REF-005 — Concurrency ownership
`PaymentIntent` owns the refundable-amount invariant and the locking needed to protect it.

Refund module must reserve/release/complete refund amounts through Payment's public command API.

### Reservation lifecycle

On refund creation:

```text
reservedRefundAmount += refundAmount
```

On refund success:

```text
reservedRefundAmount -= refundAmount
refundedAmount       += refundAmount
```

On refund failure:

```text
reservedRefundAmount -= refundAmount
```

## Idempotency

### BR-IDEM-001 — Required financial idempotency
The following operations require `Idempotency-Key`:

- create PaymentIntent;
- confirm PaymentIntent;
- create Refund.

### BR-IDEM-002 — Scope
Uniqueness scope:

```text
merchant + operation + idempotencyKey
```

### BR-IDEM-003 — Same request replay
Same scope + same request hash returns the existing logical result and does not create a second financial operation.

### BR-IDEM-004 — Key reuse with different request
Same scope + different request hash returns `409 IDEMPOTENCY_KEY_REUSED`.

### BR-IDEM-005 — Concurrent duplicate
Concurrent duplicate requests are resolved using a database unique constraint, not only application pre-checks.

## Ledger

### BR-LED-001 — Double entry
Each ledger transaction contains at least one debit and one credit entry.

### BR-LED-002 — Balanced posting
For every ledger transaction:

```text
sum(DEBIT) == sum(CREDIT)
```

### BR-LED-003 — Append-only
Completed ledger transactions and entries are immutable.

Corrections are represented by reversal postings.

### BR-LED-004 — Idempotent posting
A business reference may not create the same posting type more than once.

## Webhook

### BR-WEB-001 — Delivery guarantee
Webhook delivery is at-least-once, not exactly-once.

### BR-WEB-002 — Stable event ID
Every event has a unique public event ID so merchants can deduplicate deliveries.

### BR-WEB-003 — Signature
Webhook payloads are signed using HMAC-SHA256 over:

```text
timestamp + "." + rawRequestBody
```

Header format:

```text
FlowPay-Signature: t=<timestamp>,v1=<signature>
```

Recommended timestamp tolerance: 5 minutes.

### BR-WEB-004 — Retry
Failed/timeout deliveries are retried with backoff until delivered or transitioned to a terminal dead state.

## API Keys

### BR-KEY-001 — Secret shown once
Raw API key is returned only at creation time.

### BR-KEY-002 — No plaintext storage
The database stores a lookup prefix and secure hash, never the full plaintext API key.

### BR-KEY-003 — Revoke, do not delete
Revoking an API key transitions it to `REVOKED`; the business record remains stored.

## Money

### BR-MON-001 — Minor units
Money is stored as integer minor units.

Examples:

```text
500000 VND -> 500000
10.50 USD  -> 1050
```

### BR-MON-002 — No floating point
`float` and `double` must not be used for financial amounts.
