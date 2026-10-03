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

The header is required and must not be blank. Keys are case-sensitive and are
scoped without trimming or case normalization.

Required for:

- create PaymentIntent.
- confirm PaymentIntent.
- create Refund.

Scope:

`merchant + operation + key`

Every completed equivalent replay returns the stored previous logical result
and includes:

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

A completed Create replay returns the same `201` status, public response body,
and `Location` value as the original response, adds
`Idempotency-Replayed: true`, and creates no second PaymentIntent.

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

Currency is derived from the payment. Reason is optional, trimmed, blank-normalized
to `null`, and limited to 255 characters.

Provider outcomes:

```text
SUCCESS            -> 201 Created, Refund SUCCEEDED
DECLINED           -> 201 Created, Refund FAILED
TECHNICAL_FAILURE  -> 201 Created, Refund FAILED
UNKNOWN            -> 202 Accepted, Refund PROCESSING
```

Every created outcome includes:

```http
Location: /api/v1/refunds/re_01K...
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
    "provider": "SIMULATOR",
    "providerRefundId": "sim_refund_01K...",
    "failureCode": null,
    "failureMessage": null,
    "createdAt": "2026-08-18T03:00:00Z",
    "updatedAt": "2026-08-18T03:00:01Z",
    "completedAt": "2026-08-18T03:00:01Z"
  }
}
```

The same response fields are used by create, retrieve, list, and the stored
Idempotency snapshot. Non-applicable values remain explicit JSON `null` values.
A completed replay returns the original status, body, and `Location`, adds
`Idempotency-Replayed: true`, and never invokes the provider or mutates Payment
capacity again.

Exceeds refundable amount:

```http
409 Conflict
```

`REFUND_AMOUNT_EXCEEDS_AVAILABLE`

Invalid Payment state:

```http
409 Conflict
```

`REFUND_INVALID_PAYMENT_STATE`

### Retrieve refund

```http
GET /api/v1/refunds/{refundId}
```

### List refunds for payment

```http
GET /api/v1/payment-intents/{paymentId}/refunds
```

Defaults are page `0`, size `20`, maximum size `100`, ordered by `createdAt DESC`.
All Refund endpoints require merchant API-key authentication and cross-merchant
Payment/Refund access behaves as not found.

## 13. Webhook endpoint management

Dashboard JWT required.

All endpoint identifiers are public `wep_...` IDs. Unknown and cross-merchant
resources both return `404`.

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

Returns `201` with `Location: /api/v1/merchant/webhook-endpoints/{endpointId}`.
The raw secret is returned only in this response:

```json
{
  "data": {
    "id": "wep_01K...",
    "url": "https://example.com/api/webhooks/flowpay",
    "status": "ACTIVE",
    "events": ["payment.failed", "payment.succeeded", "refund.succeeded"],
    "secret": "whsec_...",
    "createdAt": "2026-09-13T03:00:00Z",
    "updatedAt": "2026-09-13T03:00:00Z"
  }
}
```

### List and retrieve

```http
GET /api/v1/merchant/webhook-endpoints
GET /api/v1/merchant/webhook-endpoints/{endpointId}
```

Endpoint reads return `id`, `url`, `status`, sorted `events`, `createdAt`, and
`updatedAt`. They never return the raw secret or encrypted secret.

List returns `{ "data": [...] }` (not paginated), includes ACTIVE and DISABLED
endpoints, and orders by `createdAt DESC` with an internal ID tie-breaker.

### Update

```http
PATCH /api/v1/merchant/webhook-endpoints/{endpointId}
```

`url` and `events` are optional, but at least one must be supplied. When present,
`events` replaces the complete non-empty subscription set. Only ACTIVE endpoints
can be updated.

Omit a field to leave it unchanged; explicit `null` values are invalid.
Event names must exactly match the six canonical public event names; duplicates
are invalid. PATCH and secret rotation on DISABLED endpoints return
`409 WEBHOOK_INVALID_STATE`. Concurrent mutation conflicts also return
`409 WEBHOOK_INVALID_STATE`; reload the endpoint before retrying.

### Disable

```http
DELETE /api/v1/merchant/webhook-endpoints/{endpointId}
```

Returns `204` and changes status to `DISABLED`.

The status change and cancellation of all PENDING/RETRYING deliveries commit
atomically. Cancelled deliveries become DEAD with `ENDPOINT_DISABLED`, their
next schedule is cleared, and attempt counts/history are preserved. Already
DELIVERING requests may finish successfully; failure or lease expiry after
disable becomes DEAD instead of scheduling another retry.

Repeating DELETE for an already DISABLED endpoint also returns `204`.
Endpoint configuration and subscriptions are retained; there is no re-enable API.

### Rotate secret

```http
POST /api/v1/merchant/webhook-endpoints/{endpointId}/rotate-secret
```

Returns the new raw secret once.

```json
{
  "data": {
    "id": "wep_01K...",
    "secret": "whsec_...",
    "updatedAt": "2026-09-13T03:05:00Z"
  }
}
```

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
      "amount": 500000,
      "currency": "VND",
      "status": "SUCCEEDED",
      "failureCode": null,
      "failureMessage": null
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

FlowPay signs the ASCII Unix-seconds timestamp, a literal `.`, and the exact body
bytes sent in the POST. Outbound requests also send
`User-Agent: FlowPay-Webhooks/1.0`. Redirects are never followed. All HTTP 2xx
statuses acknowledge delivery; other final statuses, connection/request timeouts,
TLS, and transport errors fail the attempt. Configurable connection/request
timeouts default to two/five seconds. Merchant response bodies are not read or
retained; acknowledgement is based on the final response status/headers.

`createdAt` is the source event occurrence time. Processing/failed variants use
the matching event type and status; failed variants contain only bounded,
normalized failure fields. The same event ID and exact body are reused for every
attempt, while the signature timestamp/signature may change. Cross-event ordering
is not guaranteed, so merchants must deduplicate by `FlowPay-Event-Id` and
tolerate out-of-order state notifications.

Failure codes are trimmed and limited to 64 Unicode characters; failure messages
are trimmed and limited to 255 Unicode characters, using only normalized source
failure facts (never raw provider responses or exceptions). Non-failed events
include both fields as JSON `null`. Payment bodies omit `orderId`: the frozen
source V1 contract does not supply it, and Webhook does not query current state
to enrich historical events. Public bodies never contain internal merchant IDs.

Refund payload example:

```json
{
  "id": "evt_01K...",
  "type": "refund.succeeded",
  "createdAt": "2026-08-18T03:05:00Z",
  "data": {
    "refund": {
      "id": "re_01K...",
      "paymentId": "pi_01K...",
      "amount": 100000,
      "currency": "VND",
      "status": "SUCCEEDED",
      "failureCode": null,
      "failureMessage": null
    }
  }
}
```

Processing/failed Refund variants use the corresponding type and status, just
like Payment. An event is materialized once even with no ACTIVE subscribers.
Delivery subscriptions are snapshotted on its first materialization; redelivery
does not include endpoints configured afterward.

### Delivery history

```http
GET /api/v1/merchant/webhook-deliveries
GET /api/v1/merchant/webhook-deliveries/{deliveryId}
```

List query parameters are `status`, `endpointId`, `eventType`, `page`, and
`size`. Defaults are page `0`, size `20`, maximum size `100`, ordered by
`createdAt DESC`, then internal row ID descending as a stable tie-breaker (the
internal ID is never returned). `status` accepts `PENDING`, `DELIVERING`,
`DELIVERED`, `RETRYING`, or `DEAD`; `eventType` accepts the supported lowercase
dot-separated public event names. Invalid filters or pagination return
`400 VALIDATION_ERROR`. Unknown or foreign `endpointId` filters produce an empty
list, without revealing whether that endpoint exists.

A delivery summary exposes:

```text
id, endpointId, eventId, eventType, resourceType, resourceId,
status, attemptCount, nextAttemptAt, deliveredAt,
lastHttpStatus, lastError, createdAt, updatedAt
```

The detail response adds attempts ordered by `attemptNo ASC`, each containing:

```text
attemptNo, startedAt, finishedAt, httpStatus, durationMs, errorMessage
```

Internal IDs, source integration-event IDs, entity versions, endpoint secrets,
and ciphertext are never exposed.

All delivery routes require dashboard JWT authentication, not merchant API keys.
Unknown or cross-merchant delivery IDs return `404 WEBHOOK_DELIVERY_NOT_FOUND`.

### Manual retry

```http
POST /api/v1/merchant/webhook-deliveries/{deliveryId}/retry
```

Allowed only for a merchant-owned DEAD delivery whose endpoint remains ACTIVE.
It schedules asynchronous retry and does not perform outbound HTTP inline.

Success:

```http
202 Accepted
```

The response body contains the newly scheduled delivery summary. Attempt count
and attempt history are not reset.

A non-DEAD delivery, including DELIVERED, or a disabled endpoint returns
`409 WEBHOOK_INVALID_STATE`. Concurrent requests recheck database-locked state;
only one can schedule the same DEAD delivery. The worker later creates the next
attempt number, rather than resetting the automatic retry budget or history.

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
- `WEBHOOK_DELIVERY_NOT_FOUND`

Rate limiting:

- `RATE_LIMIT_EXCEEDED`

Implementation exceptions, SQL names, Hibernate messages, stack traces, and internal IDs must not leak through this contract.
