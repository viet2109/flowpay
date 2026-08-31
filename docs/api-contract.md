# FlowPay — API Contract v1

Base path:

`/api/v1`

Public APIs expose public resource IDs only. Internal database IDs are never returned.

## 1. Success envelope

Single resource:

```json
{
  "data": {
    "...": "..."
  }
}
```

Collection:

```json
{
  "data": [],
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 125,
    "totalPages": 7,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

Default pagination:

- page = 0
- size = 20
- maximum size = 100
- default sort = createdAt descending

## 2. Problem Details

Errors use:

`Content-Type: application/problem+json`

Example:

```json
{
  "type": "https://flowpay.dev/problems/idempotency-key-reused",
  "title": "Idempotency key reused",
  "status": 409,
  "detail": "The idempotency key was already used with a different request.",
  "instance": "/api/v1/payment-intents",
  "code": "IDEMPOTENCY_KEY_REUSED",
  "requestId": "req_01K..."
}
```

Validation:

```json
{
  "type": "https://flowpay.dev/problems/validation-error",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request validation failed.",
  "instance": "/api/v1/payment-intents",
  "code": "VALIDATION_ERROR",
  "requestId": "req_01K...",
  "errors": [
    {
      "field": "amount",
      "code": "POSITIVE",
      "message": "Amount must be greater than zero."
    }
  ]
}
```

Client behavior should depend on stable `code`, not human-readable `detail`.

## 3. Request correlation

Request header:

`X-Request-Id`

If a valid request ID is supplied, FlowPay may propagate it. Otherwise FlowPay generates one.

Response always includes:

`X-Request-Id`

Example format:

`req_01K...`

## 4. Authentication models

### Dashboard APIs

Use:

`Authorization: Bearer <JWT>`

### Merchant integration APIs

Use:

`Authorization: Bearer fp_test_<secret>`

Merchant identity is derived from authentication context.

Clients do not send `merchantId` to select ownership.

## 5. Dashboard access token

JWT policy:

- RSA asymmetric signing.
- default TTL = 15 minutes.
- subject = user public ID.
- claims include active merchant public ID and role in MVP.

Example claims:

```json
{
  "sub": "usr_01K...",
  "merchant": "mrc_01K...",
  "role": "OWNER"
}
```

## 6. Refresh token

Refresh token policy:

- opaque cryptographically random token.
- default TTL = 7 days.
- rotating.
- stored in HttpOnly cookie.
- raw token is not returned in JSON.

Cookie:

```text
Name     flowpay_refresh
HttpOnly true
SameSite Lax
Path     /api/v1/auth
Secure   true in production
```

## 7. Auth endpoints

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

Rules:

- email trimmed/lowercased.
- valid email.
- password minimum 8 characters.
- merchant name required.

Success:

```http
201 Created
```

```json
{
  "data": {
    "user": {
      "id": "usr_01K...",
      "email": "viet@example.com",
      "firstName": "Viet",
      "lastName": "Nguyen"
    },
    "merchant": {
      "id": "mrc_01K...",
      "name": "ABC Store",
      "status": "ACTIVE"
    }
  }
}
```

Duplicate email:

```http
409 Conflict
```

`USER_EMAIL_ALREADY_EXISTS`

Registration does not perform email verification in MVP Phase 1.

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

Success:

```http
200 OK
Set-Cookie: flowpay_refresh=<opaque>; HttpOnly; ...
```

```json
{
  "data": {
    "accessToken": "eyJ...",
    "expiresIn": 900,
    "user": {
      "id": "usr_01K...",
      "email": "viet@example.com"
    }
  }
}
```

Unknown email and wrong password both return:

```http
401 Unauthorized
```

`INVALID_CREDENTIALS`

### Refresh

```http
POST /api/v1/auth/refresh
Cookie: flowpay_refresh=<opaque>
```

Success:

- consumes/rotates the current refresh token.
- sets a replacement cookie.
- returns a new access token.

```json
{
  "data": {
    "accessToken": "eyJ...",
    "expiresIn": 900
  }
}
```

Errors include:

- `REFRESH_TOKEN_INVALID`
- `REFRESH_TOKEN_EXPIRED`
- `REFRESH_TOKEN_REVOKED`

### Logout

```http
POST /api/v1/auth/logout
Cookie: flowpay_refresh=<opaque>
```

Success:

```http
204 No Content
```

Server revokes the token when present and clears the cookie.

## 8. Merchant profile

Dashboard JWT required.

### Get

```http
GET /api/v1/merchant
```

```json
{
  "data": {
    "id": "mrc_01K...",
    "name": "ABC Store",
    "status": "ACTIVE",
    "createdAt": "2026-08-18T03:00:00Z"
  }
}
```

### Update

```http
PATCH /api/v1/merchant
```

Request:

```json
{
  "name": "ABC Technology Store"
}
```

Client cannot modify merchant status through this generic profile endpoint.

## 9. API key management

Dashboard JWT required.

### Create

```http
POST /api/v1/merchant/api-keys
```

```json
{
  "name": "Development backend"
}
```

Success:

```http
201 Created
```

```json
{
  "data": {
    "id": "key_01K...",
    "name": "Development backend",
    "key": "fp_test_A7x...",
    "prefix": "fp_test_A7x",
    "status": "ACTIVE",
    "createdAt": "2026-08-18T03:00:00Z"
  }
}
```

The `key` field appears only in this create response.

### List

```http
GET /api/v1/merchant/api-keys
```

Response never exposes raw key or key hash.

### Revoke

```http
DELETE /api/v1/merchant/api-keys/{keyId}
```

Success:

```http
204 No Content
```

This performs:

`ACTIVE -> REVOKED`

The row is not deleted.

Cross-merchant resource access behaves as not found.

## 10. Idempotency

Header:

`Idempotency-Key: <client-generated-key>`

Maximum length:

`255`

Required for:

- create PaymentIntent.
- confirm PaymentIntent.
- create Refund.

Scope:

`merchant + operation + key`

Equivalent replay returns the previous logical result and may include:

`Idempotency-Replayed: true`

Same key with different request:

```http
409 Conflict
```

`IDEMPOTENCY_KEY_REUSED`

A duplicate request while the original is still processing may return:

```http
409 Conflict
```

`IDEMPOTENCY_REQUEST_IN_PROGRESS`

Retention target for MVP:

`24 hours`

## 11. PaymentIntent

Merchant API-key authentication required.

Phase availability:

- Phase 3 exposes create and confirm with mandatory idempotency protection,
  along with the Phase 2 retrieve, list, and transaction-attempt endpoints.

### Create

```http
POST /api/v1/payment-intents
Authorization: Bearer fp_test_...
Idempotency-Key: order-2026-001-create
```

Request:

```json
{
  "amount": 500000,
  "currency": "VND",
  "orderId": "ORDER-2026-001",
  "description": "Payment for ORDER-2026-001"
}
```

`amount` is in currency minor units.

Success:

```http
201 Created
Location: /api/v1/payment-intents/pi_01K...
```

```json
{
  "data": {
    "id": "pi_01K...",
    "orderId": "ORDER-2026-001",
    "amount": 500000,
    "currency": "VND",
    "status": "CREATED",
    "refundedAmount": 0,
    "refundableAmount": 0,
    "description": "Payment for ORDER-2026-001",
    "createdAt": "2026-08-18T03:00:00Z"
  }
}
```

### Confirm

```http
POST /api/v1/payment-intents/{paymentId}/confirm
Authorization: Bearer fp_test_...
Idempotency-Key: order-2026-001-confirm
```

No request body is required in MVP.

Provider success:

```http
200 OK
```

Payment status `SUCCEEDED`.

Provider decline:

```http
200 OK
```

Payment status `FAILED` with transaction failure code.

Provider unknown/timeout:

```http
202 Accepted
```

Payment status `PROCESSING`.

Latest transaction status `UNKNOWN`.

The original result and every completed replay use the same logical response
snapshot. The standard `data` envelope contains:

```json
{
  "data": {
    "paymentId": "pi_01K...",
    "paymentStatus": "SUCCEEDED",
    "transactionId": "ptxn_01K...",
    "transactionStatus": "SUCCEEDED",
    "provider": "SIMULATOR",
    "providerTransactionId": "sim_01K...",
    "failureCode": null,
    "failureMessage": null
  }
}
```

The persisted snapshot contains only these public normalized fields. It does not
contain internal IDs, entities, or raw provider payloads. A replay uses the
stored snapshot and original HTTP status without rebuilding either from current
Payment state.

A completed replay returns the exact stored logical response with the original
HTTP status and:

```http
Idempotency-Replayed: true
```

Invalid payment state:

```http
409 Conflict
```

`PAYMENT_INVALID_STATE`

### Retrieve

```http
GET /api/v1/payment-intents/{paymentId}
```

### List

```http
GET /api/v1/payment-intents
```

Supported filters:

- `status`
- `orderId`
- `createdFrom`
- `createdTo`
- `page`
- `size`

### Transaction attempts

```http
GET /api/v1/payment-intents/{paymentId}/transactions
```

## 12. Refund

Merchant API-key authentication required.

### Create refund

```http
POST /api/v1/payment-intents/{paymentId}/refunds
Authorization: Bearer fp_test_...
Idempotency-Key: refund-order-001-item-a
```

Request:

```json
{
  "amount": 200000,
  "reason": "CUSTOMER_REQUEST"
}
```

Currency is derived from the payment.

Success:

```http
201 Created
```

```json
{
  "data": {
    "id": "re_01K...",
    "paymentId": "pi_01K...",
    "amount": 200000,
    "currency": "VND",
    "status": "SUCCEEDED",
    "reason": "CUSTOMER_REQUEST",
    "createdAt": "2026-08-18T03:00:00Z"
  }
}
```

Exceeds refundable amount:

```http
409 Conflict
```

`REFUND_AMOUNT_EXCEEDS_AVAILABLE`

### Retrieve refund

```http
GET /api/v1/refunds/{refundId}
```

### List refunds for payment

```http
GET /api/v1/payment-intents/{paymentId}/refunds
```

## 13. Webhook endpoint management

Dashboard JWT required.

### Create endpoint

```http
POST /api/v1/merchant/webhook-endpoints
```

```json
{
  "url": "https://example.com/api/webhooks/flowpay",
  "events": [
    "payment.succeeded",
    "payment.failed",
    "refund.succeeded"
  ]
}
```

Create response returns the webhook secret once.

### Update

```http
PATCH /api/v1/merchant/webhook-endpoints/{endpointId}
```

### Disable

```http
DELETE /api/v1/merchant/webhook-endpoints/{endpointId}
```

Returns `204` and changes status to `DISABLED`.

### Rotate secret

```http
POST /api/v1/merchant/webhook-endpoints/{endpointId}/rotate-secret
```

Returns the new raw secret once.

## 14. Webhook delivery

Webhook request example:

```http
POST https://merchant.example/webhooks/flowpay
Content-Type: application/json
FlowPay-Signature: t=1786849200,v1=<signature>
FlowPay-Event-Id: evt_01K...
```

Payload:

```json
{
  "id": "evt_01K...",
  "type": "payment.succeeded",
  "createdAt": "2026-08-18T03:00:00Z",
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

Signed input:

`timestamp + "." + rawRequestBody`

Algorithm:

`HMAC-SHA256`

Default timestamp tolerance:

`5 minutes`

### Delivery history

```http
GET /api/v1/merchant/webhook-deliveries
GET /api/v1/merchant/webhook-deliveries/{deliveryId}
```

### Manual retry

```http
POST /api/v1/merchant/webhook-deliveries/{deliveryId}/retry
```

Intended for dead/failed deliveries.

Success:

```http
202 Accepted
```

## 15. Event types v1

- `payment.processing`
- `payment.succeeded`
- `payment.failed`
- `refund.processing`
- `refund.succeeded`
- `refund.failed`

## 16. HTTP status policy

| Situation | Status |
|---|---:|
| Resource created | 201 |
| Successful query/command | 200 |
| Accepted/pending async outcome | 202 |
| Revoke/disable/logout success | 204 |
| Validation/invalid JSON | 400 |
| Missing/invalid authentication | 401 |
| Authenticated but forbidden | 403 |
| Not found / cross-merchant | 404 |
| State, concurrency, idempotency conflict | 409 |
| Unsupported media type | 415 |
| Rate limit | 429 |
| Unexpected error | 500 |
| Unavailable dependency when operation cannot be accepted | 503 |

## 17. Error codes

Foundation:

- `VALIDATION_ERROR`
- `AUTHENTICATION_REQUIRED`
- `INTERNAL_ERROR`

Identity:

- `USER_EMAIL_ALREADY_EXISTS`
- `INVALID_CREDENTIALS`
- `USER_LOCKED`
- `USER_DISABLED`
- `REFRESH_TOKEN_INVALID`
- `REFRESH_TOKEN_EXPIRED`
- `REFRESH_TOKEN_REVOKED`

Merchant/API key:

- `MERCHANT_NOT_FOUND`
- `MERCHANT_SUSPENDED`
- `API_KEY_NOT_FOUND`
- `API_KEY_REVOKED`
- `INVALID_API_KEY`

Payment:

- `PAYMENT_NOT_FOUND`
- `PAYMENT_INVALID_STATE`
- `PAYMENT_ALREADY_SUCCEEDED`

Refund:

- `REFUND_NOT_FOUND`
- `REFUND_INVALID_PAYMENT_STATE`
- `REFUND_AMOUNT_EXCEEDS_AVAILABLE`

Idempotency:

- `IDEMPOTENCY_KEY_REQUIRED`
- `IDEMPOTENCY_KEY_REUSED`
- `IDEMPOTENCY_REQUEST_IN_PROGRESS`

Webhook:

- `WEBHOOK_ENDPOINT_NOT_FOUND`
- `WEBHOOK_INVALID_STATE`

Rate limiting:

- `RATE_LIMIT_EXCEEDED`

Implementation exceptions, SQL names, Hibernate messages, stack traces, and internal IDs must not leak through this contract.
