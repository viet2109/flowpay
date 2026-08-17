# FlowPay — Product Requirements v1

## Problem Statement

FlowPay is a payment processing platform that lets merchants integrate a unified API to create, process, track, and refund payments.

The system must prevent accidental duplicate financial operations, preserve financial consistency, handle ambiguous or failing provider interactions, and reliably notify merchants of state changes through webhooks.

FlowPay v1 uses a simulated payment provider. It does not process real card or banking data.

## Portfolio Goals

The project should demonstrate:

- Java/Spring engineering;
- transaction design;
- concurrency control;
- idempotency;
- modular architecture;
- event-driven processing;
- transactional outbox;
- reliable webhooks;
- double-entry ledger fundamentals;
- security practices;
- testing with real infrastructure;
- observability and DevOps maturity.

## Actors

### Merchant Admin
Uses the FlowPay dashboard to manage merchant settings, API keys, webhooks, payments, refunds, and delivery logs.

### Merchant Backend
Calls FlowPay integration APIs to create/confirm payments and create refunds.

### Customer
Pays the merchant. The customer does not need a FlowPay account in v1.

### Payment Provider
External payment processor abstraction. v1 uses `FLOWPAY_SIMULATOR`.

### FlowPay Admin
Operational/support actor. Admin product scope is not part of the initial MVP implementation.

## MVP Capabilities

### Identity and Merchant

- register merchant owner;
- login;
- merchant profile;
- create/list/revoke API keys.

### Payment

- create PaymentIntent;
- confirm PaymentIntent;
- retrieve payment;
- list payments;
- view provider attempts;
- provider simulator outcomes: success, decline/failure, timeout/unknown.

### Refund

- full refund;
- partial refund;
- multiple partial refunds;
- concurrency-safe refundable amount enforcement.

### Ledger

- double-entry posting for successful payments and refunds;
- immutable postings;
- duplicate-posting protection.

### Webhook

- configure endpoint;
- subscribe to event types;
- signed delivery;
- retry with backoff;
- delivery attempt history;
- manual retry of dead deliveries.

### Reliability

- idempotency keys;
- transactional outbox;
- RabbitMQ integration;
- retry/dead-letter handling where appropriate.

## Explicitly Out of Scope for Initial MVP

- real card data;
- PCI-DSS implementation;
- real banking integration;
- real settlement;
- KYC;
- AML;
- fraud detection;
- chargebacks/disputes;
- multi-provider routing;
- FX conversion;
- multi-region architecture;
- microservices decomposition.

## Delivery Strategy

FlowPay starts as a modular monolith.

Suggested implementation phases:

1. Foundation and project bootstrap.
2. Identity, Merchant, API keys.
3. Payment core and provider simulator.
4. Idempotency.
5. Refund and concurrency.
6. Ledger.
7. Outbox and RabbitMQ.
8. Webhook delivery.
9. Observability, CI/CD, load/reliability hardening.
