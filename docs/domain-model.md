# FlowPay — Domain Model v1

## Modules and Aggregate Roots

| Module | Aggregate root |
|---|---|
| identity | User |
| merchant | Merchant |
| merchant | ApiKey |
| payment | PaymentIntent |
| payment | PaymentTransaction |
| refund | Refund |
| ledger | LedgerAccount |
| ledger | LedgerTransaction |
| webhook | WebhookEndpoint |
| webhook | WebhookEvent |
| webhook | WebhookDelivery |

`LedgerEntry` is owned by `LedgerTransaction` and is not a standalone aggregate root.

## Identity and Merchant

`User` represents a dashboard identity.

`Merchant` represents the business account.

These are not the same concept.

A merchant may eventually have multiple users through `MerchantMember` roles such as:

```text
OWNER
DEVELOPER
FINANCE
VIEWER
```

## PaymentIntent

Represents the merchant's intent to collect a fixed amount/currency for a merchant order/reference.

Core state:

```text
merchantId
orderId
amount
currency
status
refundedAmount
reservedRefundAmount
version
```

The aggregate owns payment state transitions and refundable-amount invariants.

## PaymentTransaction

Represents one provider-processing attempt for a PaymentIntent.

A PaymentIntent may have multiple attempts.

Example:

```text
PaymentIntent PI-1
├── attempt 1 -> UNKNOWN
└── attempt 2 -> SUCCEEDED
```

PaymentTransaction has its own lifecycle and is a separate aggregate root.

## Refund

Refund is a separate aggregate root with its own lifecycle:

```text
CREATED
PROCESSING
SUCCEEDED
FAILED
```

Refund stores a reference to `paymentIntentId` but does not own or directly mutate PaymentIntent persistence.

## Ledger

`LedgerTransaction` owns a collection of `LedgerEntry` values/entities.

Example payment posting:

```text
LedgerTransaction
├── DEBIT  CUSTOMER_CLEARING  500000
└── CREDIT MERCHANT_PAYABLE   500000
```

`LedgerAccount` is a separate aggregate root.

Ledger should support future posting types such as:

```text
PAYMENT_CAPTURE
REFUND
FEE
SETTLEMENT
ADJUSTMENT
REVERSAL
```

Only payment/refund are required initially.

## Webhook

`WebhookEndpoint` stores merchant configuration and encrypted signing secret.

`WebhookEvent` is immutable business-delivery content.

`WebhookDelivery` tracks delivery lifecycle to one endpoint.

Individual HTTP attempts are child records of WebhookDelivery.

## Cross-Module Communication

### Synchronous query
Use a small public module API if another module needs data before making a business decision.

Example:

```text
Refund -> PaymentQueryApi -> PaymentSnapshot
```

### Synchronous command
A module may expose a narrow public command API when another module must ask the aggregate owner to mutate state while preserving invariants.

Example:

```text
Refund -> PaymentRefundApi.reserveRefund(...)
Refund -> PaymentRefundApi.completeRefund(...)
Refund -> PaymentRefundApi.releaseRefund(...)
```

### Side effects
Prefer integration events.

```text
payment.succeeded -> Ledger
payment.succeeded -> Webhook
refund.succeeded  -> Ledger
refund.succeeded  -> Webhook
```

## Reference Rules

- aggregates reference other aggregates by ID;
- no cross-module JPA object relationships;
- no external module receives another module's JPA entity;
- internal database IDs may be used internally but are never exposed through public HTTP APIs.
