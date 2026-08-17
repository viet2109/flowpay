# FlowPay — API Contract v1

## General

Base path:

```text
/api/v1
```

All public resource identifiers use public prefixed IDs.

Internal database IDs are never returned.

## Success Envelope

Single object:

```json
{
  "data": {}
}
```

Paged response:

```json
{
  "data": [],
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 0,
    "totalPages": 0,
    "hasNext": false,
    "hasPrevious": false
  }
}
```

Default paging:

```text
page = 0
size = 20
sort = createdAt,DESC
max size = 100
```

## Error Model

Use RFC 9457 Problem Details (`application/problem+json`) with extensions:

```json
{
  "type": "https://flowpay.dev/problems/example",
  "title": "Example problem",
  "status": 409,
  "detail": "Human-readable detail",
  "instance": "/api/v1/...",
  "code": "STABLE_MACHINE_CODE",
  "requestId": "req_01K..."
}
```

Validation errors may include:

```json
{
  "code": "VALIDATION_ERROR",
  "errors": [
    {
      "field": "amount",
      "code": "POSITIVE",
      "message": "Amount must be greater than zero."
    }
  ]
}
```

Client logic should rely on `code`, not localized text.

## Request Correlation

Response includes:

```text
X-Request-Id: req_...
```

## Authentication

### Dashboard APIs

```text
Authorization: Bearer <JWT>
```

### Merchant Integration APIs

```text
Authorization: Bearer fp_test_<secret>
```

`merchantId` is derived from authentication and must not be accepted from request bodies for merchant-scoped integration operations.

## Idempotency

Header:

```text
Idempotency-Key: <client key>
```

Maximum length: 255 characters.

Required for:

- `POST /payment-intents`
- `POST /payment-intents/{id}/confirm`
- `POST /payment-intents/{id}/refunds`

Retention target: 24 hours.

Same request replay may return:

```text
Idempotency-Replayed: true
```

Conflicts:

```text
IDEMPOTENCY_KEY_REUSED
IDEMPOTENCY_REQUEST_IN_PROGRESS
```

## Auth Endpoints

### Register

```http
POST /api/v1/auth/register
```

Request:

```json
{
  "email": "viet@example.com",
  "password": "StrongPassword123!",
  "firstName": "Viet",
  "lastName": "Nguyen",
  "merchantName": "ABC Store"
}
```

Response: `201 Created`.

v1 does not require email verification.

### Login

```http
POST /api/v1/auth/login
```

Request:

```json
{
  "email": "viet@example.com",
  "password": "StrongPassword123!"
}
```

Access token is returned in the response body.

Refresh token should use an HttpOnly Secure cookie.

## Merchant Endpoints

```text
GET    /api/v1/merchant
PATCH  /api/v1/merchant

POST   /api/v1/merchant/api-keys
GET    /api/v1/merchant/api-keys
DELETE /api/v1/merchant/api-keys/{id}

POST   /api/v1/merchant/webhook-endpoints
GET    /api/v1/merchant/webhook-endpoints
PATCH  /api/v1/merchant/webhook-endpoints/{id}
DELETE /api/v1/merchant/webhook-endpoints/{id}
POST   /api/v1/merchant/webhook-endpoints/{id}/rotate-secret

GET    /api/v1/merchant/webhook-deliveries
GET    /api/v1/merchant/webhook-deliveries/{id}
POST   /api/v1/merchant/webhook-deliveries/{id}/retry
```

API key raw secret is shown once on creation.

Deleting/revoking API keys and webhook endpoints is a soft business state transition, not physical deletion.

## Payment Endpoints

### Create PaymentIntent

```http
POST /api/v1/payment-intents
Authorization: Bearer fp_test_...
Idempotency-Key: order-123-create
```

Request:

```json
{
  "amount": 500000,
  "currency": "VND",
  "orderId": "ORDER-123",
  "description": "Payment for ORDER-123"
}
```

`amount` is expressed in minor units.

Response: `201 Created` and a `Location` header.

Initial status: `CREATED`.

### Confirm Payment

```http
POST /api/v1/payment-intents/{paymentId}/confirm
Idempotency-Key: order-123-confirm
```

No body is required in v1.

Provider success:

```text
HTTP 200
PaymentIntent = SUCCEEDED
```

Provider decline:

```text
HTTP 200
PaymentIntent = FAILED
```

A declined payment is a business result, not an HTTP transport/server failure.

Ambiguous provider timeout:

```text
HTTP 202
PaymentIntent = PROCESSING
latest PaymentTransaction = UNKNOWN
```

Invalid state transition:

```text
HTTP 409
PAYMENT_INVALID_STATE
```

### Retrieve Payment

```http
GET /api/v1/payment-intents/{paymentId}
```

Response may include latest provider transaction summary and derived `refundableAmount`.

Cross-merchant access returns `404 PAYMENT_NOT_FOUND`.

### List Payments

```http
GET /api/v1/payment-intents
```

Supported v1 filters:

```text
page
size
status
orderId
createdFrom
createdTo
```

### Payment Transactions

```http
GET /api/v1/payment-intents/{paymentId}/transactions
```

Returns provider attempts ordered by attempt number/time.

## Refund Endpoints

### Create Refund

```http
POST /api/v1/payment-intents/{paymentId}/refunds
Idempotency-Key: refund-order-123-item-a
```

Request:

```json
{
  "amount": 200000,
  "reason": "CUSTOMER_REQUEST"
}
```

Currency is inherited from the payment and is not supplied by the caller.

Response: `201 Created` for a created refund resource.

If requested amount exceeds currently refundable amount:

```text
HTTP 409
REFUND_AMOUNT_EXCEEDS_AVAILABLE
```

### List Payment Refunds

```http
GET /api/v1/payment-intents/{paymentId}/refunds
```

### Retrieve Refund

```http
GET /api/v1/refunds/{refundId}
```

## Webhook Event Types v1

```text
payment.processing
payment.succeeded
payment.failed
refund.processing
refund.succeeded
refund.failed
```

## Webhook Payload

Example:

```json
{
  "id": "evt_01K...",
  "type": "payment.succeeded",
  "createdAt": "2026-08-16T03:00:00Z",
  "data": {
    "payment": {
      "id": "pi_01K...",
      "orderId": "ORDER-001",
      "amount": 500000,
      "currency": "VND",
      "status": "SUCCEEDED"
    }
  }
}
```

Headers:

```text
FlowPay-Signature: t=<timestamp>,v1=<signature>
FlowPay-Event-Id: evt_...
```

Signature algorithm:

```text
HMAC-SHA256(secret, timestamp + "." + rawBody)
```

## HTTP Status Policy

| Scenario | HTTP status |
|---|---:|
| resource created | 201 |
| query/update success | 200 |
| accepted/ambiguous async processing | 202 |
| revoke/disable success | 204 |
| malformed/validation request | 400 |
| missing/invalid auth | 401 |
| authenticated but forbidden | 403 |
| resource not found / cross-merchant | 404 |
| business/idempotency/concurrency conflict | 409 |
| unsupported media type | 415 |
| rate limited | 429 |
| unexpected error | 500 |
| upstream unavailable and operation cannot be accepted | 503 |

## Stable Error Codes v1

```text
AUTHENTICATION_REQUIRED
INVALID_API_KEY
API_KEY_REVOKED

PAYMENT_NOT_FOUND
PAYMENT_INVALID_STATE
PAYMENT_ALREADY_SUCCEEDED

REFUND_NOT_FOUND
REFUND_INVALID_PAYMENT_STATE
REFUND_AMOUNT_EXCEEDS_AVAILABLE

IDEMPOTENCY_KEY_REQUIRED
IDEMPOTENCY_KEY_REUSED
IDEMPOTENCY_REQUEST_IN_PROGRESS

WEBHOOK_ENDPOINT_NOT_FOUND
WEBHOOK_INVALID_STATE

VALIDATION_ERROR
RATE_LIMIT_EXCEEDED
INTERNAL_ERROR
```
