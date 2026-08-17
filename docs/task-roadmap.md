# FlowPay — Implementation Roadmap

## Guiding Rule

Do not implement every module at once.

Each phase should leave the repository runnable, tested, and demonstrable.

## Phase 0 — Foundation

Goal: create a clean, testable Spring Boot repository without business implementation drift.

Tasks:

- bootstrap Spring Boot application;
- Java 21+;
- Maven or Gradle (choose once, document, do not switch casually);
- PostgreSQL Docker Compose;
- RabbitMQ Docker Compose;
- Flyway;
- common API response/error model;
- request ID/correlation support;
- base security configuration skeleton;
- Testcontainers base setup;
- ArchUnit dependency tests;
- module/package skeleton;
- CI pipeline running compile + tests.

Definition of done:

- application starts locally;
- PostgreSQL health is verified;
- Flyway runs successfully;
- architecture tests pass;
- integration test can start PostgreSQL Testcontainer.

## Phase 1 — Identity + Merchant

Tasks:

- users;
- merchants;
- merchant_members;
- register;
- login;
- JWT access token;
- refresh-token strategy;
- current-user/current-merchant abstractions;
- merchant profile;
- API key create/list/revoke;
- secure API-key authentication.

Important tests:

- API key raw secret is not persisted;
- revoked API key cannot authenticate;
- merchant context is derived from authenticated principal;
- merchant A cannot access merchant B resources.

## Phase 2 — Payment Core

Tasks:

- `Money` value object;
- PaymentIntent aggregate;
- PaymentTransaction aggregate;
- Flyway payment schema;
- create PaymentIntent;
- list/get PaymentIntent;
- payment state-machine tests;
- provider port;
- simulator provider;
- confirm payment split into pre-call/post-call transactions;
- success/decline/timeout outcomes.

Important tests:

- succeeded payment cannot be confirmed again;
- provider decline returns business failure state, not HTTP server error;
- timeout produces PaymentTransaction `UNKNOWN` and PaymentIntent `PROCESSING`;
- provider call is not executed inside the persistence transaction boundary.

## Phase 3 — Idempotency

Tasks:

- idempotency_records migration;
- canonical request hashing;
- create-payment idempotency;
- confirm-payment idempotency;
- replay support;
- in-progress conflict handling;
- expiration cleanup strategy.

Important tests:

- same key + same request creates one resource;
- same key + different request returns 409;
- concurrent duplicate inserts are protected by unique constraint.

## Phase 4 — Refund + Concurrency

Tasks:

- Refund aggregate;
- refund schema;
- Payment public refund command API;
- reserve refund amount;
- optimistic locking on PaymentIntent;
- process refund through simulator/provider abstraction;
- complete/release reservation;
- full and partial refund APIs.

Important tests:

- cannot refund failed/processing payment;
- cannot refund above available amount;
- two concurrent refunds cannot exceed payment amount;
- failed refund releases reservation;
- successful refund moves reserved -> refunded amount correctly.

## Phase 5 — Ledger

Tasks:

- ledger accounts;
- LedgerTransaction aggregate;
- LedgerEntry child model;
- balanced-posting invariant;
- payment success posting;
- refund success posting;
- duplicate posting protection;
- reversal model/tests.

Important tests:

- unbalanced posting cannot be created;
- duplicate event/reference cannot create duplicate posting;
- completed ledger entries are not mutable through public APIs.

## Phase 6 — Transactional Outbox + RabbitMQ

Tasks:

- outbox schema;
- IntegrationEvent abstraction;
- outbox publisher implementation;
- outbox relay;
- RabbitMQ topology;
- publisher confirms/error handling where appropriate;
- retry/dead-letter strategy;
- event-consumer idempotency.

Important tests:

- payment state and outbox event commit atomically;
- failed business transaction leaves no outbox event;
- duplicate consumer delivery does not duplicate ledger posting.

## Phase 7 — Webhooks

Tasks:

- endpoint configuration;
- encrypted webhook signing secret;
- event subscription table;
- webhook event creation from integration events;
- delivery scheduler/worker;
- HMAC signature;
- retries/backoff;
- delivery attempt history;
- dead state;
- manual retry;
- dashboard read APIs.

Important tests:

- signature verification fixture;
- duplicate integration event does not create duplicate logical delivery;
- timeout/500 triggers retry;
- 2xx marks delivered;
- disabled endpoint receives no new delivery.

## Phase 8 — Production Engineering

Tasks:

- structured logging;
- OpenTelemetry tracing;
- Prometheus metrics;
- Grafana dashboards;
- health/readiness endpoints;
- rate limiting;
- Resilience4j around provider calls where useful;
- secrets configuration;
- Docker images;
- CI/CD;
- load tests;
- failure-injection scenarios;
- README architecture diagrams and demo scenarios.

## First Coding-Agent Task

Recommended first task after importing these documents into the repository:

```text
Bootstrap the FlowPay backend foundation only.

Read AGENTS.md and all docs first.

Create a Java 21+ Spring Boot modular-monolith skeleton with:
- package roots for common, infrastructure, identity, merchant, payment, refund, ledger, webhook;
- PostgreSQL and RabbitMQ Docker Compose;
- Flyway configuration;
- initial application profiles;
- Testcontainers PostgreSQL smoke test;
- ArchUnit rules enforcing the documented module constraints;
- common API response and RFC 9457 error skeleton;
- request correlation ID infrastructure;
- CI workflow that compiles and runs tests.

Do not create business entities, payment services, refund logic, ledger logic, or public endpoints beyond infrastructure health/bootstrap needs.
Do not change the documented database/API/domain design.
Report all files changed and tests executed.
```
