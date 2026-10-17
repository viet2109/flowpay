# Production Engineering — Phase 8 Design Record

Status: P8-T01 design/exit baseline, P8-T02 configuration/management, P8-T03
business metrics, P8-T04 asynchronous operational metrics, P8-T05 synchronous
HTTP/provider/Webhook tracing, P8-T06 durable Outbox trace metadata and P8-T07
consumer/delayed-worker tracing and P8-T08 monitoring lab/dashboards/alerts
implemented through 2026-10-11; P8-T09 effective-destination/egress protection
and approved Apache transport on Java 21 are verified. P8-T10's approved auth/
ingress controls are verified on 2026-10-13. P8-T11 CI gate implementation is
verified on 2026-10-15, with security acceptance BLOCKED by unwaived baseline
findings. P8-T12 adds the verified-artifact image and isolated runtime/recovery
gates; its acceptance is recorded in [container runtime](container-runtime.md).
Phase 8 IN PROGRESS. Staging/load/recovery operations remain owned by subsequent tasks. Phase 7 remains
DONE/FROZEN; the simulator functional MVP remains complete.

This tracked document records shared scope and decision gates. The detailed
`phase-8-production-engineering.md` assignment plan is a local-only companion,
not a file to commit. `task-roadmap.md` records actual task completion.

## 1. Selected target and authority

The user selected **staging Linux/container, provider not chosen** on 2026-10-04.
Implement a reproducible isolated staging lab before claiming operational
readiness. No cloud account, paid host, external deployment, image publication
or Kubernetes cluster is selected or authorized.

Use one phase branch, `feature/phase-8-production-engineering`, based on the
verified remote main containing the complete Phase 7 release. One task at a
time; do not implement future tasks as part of the exit gate.

The phase covers metrics/tracing, monitoring, configuration/security, network
egress, CI/supply-chain checks, container packaging, staging, load/failure drills,
backup/restore and operational procedures. It does not add real provider
processing, provider inquiry/reconciliation, settlement, fees, new financial
states, microservices or a new business module.

## 2. Observed baseline versus future work

The completed source release is `d348b10` (P7-T16), merged into remote main at
`0b96400`. The merge tree equals the release tree. Baseline schema is V001-V011.

P8-T01 reran JDK21/Maven 3.9.16 `clean verify` before publication: 1,173 tests
across 171 suites, zero failures/errors/skips, 5m16s. Optional local OTLP metrics
export was disabled; no tests or business infrastructure were excluded by a
run-specific override. The selected migration/source/consumer/financial/E2E and
architecture regressions ran inside this full gate, not in a second focused
command. The merged main and initial Phase 8 branch match the tested tree, so
documentation-only preparation does not require repeating an unchanged suite.

| Area | Existing implementation | Phase 8 owner and required evidence |
|---|---|---|
| Financial consistency | Source TX + Outbox, Idempotency, reserved refund capacity, append-only balanced unique Ledger | All tasks preserve it; T14-T16/T18 recheck invariants |
| Source events | Six processing/success/known-failure facts; success V1 preserved; UNKNOWN non-terminal; T06 durable source traceparent; T07 bounded asynchronous span relations | T14-T16/T18 verify operational behavior without new business fields |
| Webhook delivery | Immutable event/subscription snapshot; claim/OPEN/lease; exact-body signed no-TX HTTP; fenced finalize; jitter/DEAD/manual retry | T04/T07/T09/T14-T16 operational evidence |
| Observability foundation | Actuator/Prometheus/OpenTelemetry, request MDC; T03/T04 bounded metrics, T05-T07 privacy-safe durable tracing, T08 isolated provisioned monitoring lab | T13 stages the released artifact; T14/T15 measure operational overhead |
| Management security | T02: dedicated loopback-by-default listener; internal scrape allowed, public Actuator denied | T13 must prove private staging network/ingress enforcement |
| URL/network safety | HTTPS/literal target checks; T09 adds fixed CONNECT/proxy network enforcement and approved Apache transport on Java 21; registration does not resolve merchant DNS | T09 verifies actual DNS/TLS/IP-SAN/rebinding/bypass; T13 proves staging isolation |
| Health/shutdown | T02: DB + availability readiness, process liveness, bounded HTTP drain verified | T12-T15 prove image signals, workers and staged lifecycle |
| CI | T11 adopts the workflow with explicit user approval; JDK21/Docker coverage and pinned supply-chain gates are implemented | Security acceptance remains blocked by baseline secret-format findings; no waiver or hosted-CI acceptance is claimed |
| Packaging/operations | T12 verified-artifact non-root image and isolated runtime/recovery gates implemented; security acceptance remains separately gated | T13-T18 own actual staging/load/failure/restore acceptance; no release while security is blocked |

This table is an inventory, not a claim that the future controls are already
available. Never document a passing Java suite as proof of load capacity, DNS
rebinding protection, image safety or successful disaster recovery.

## 3. Preserved contracts and invariants

- Keep existing public APIs, IDs, response/Problem Details shapes, financial
  Idempotency scopes and source/business event schemas unless a task explicitly
  reviews a security defect requiring a contract correction.
- Keep `payment.succeeded.v1` and `refund.succeeded.v1` payloads unchanged; Ledger
  remains success-only. UNKNOWN must not emit a false terminal event.
- Keep `refundedAmount + reservedRefundAmount <= paymentAmount` and balanced,
  duplicate-safe, append-only Ledger accounting.
- Keep source state + Outbox atomicity; broker publication/consumer retries do
  not imply exactly-once delivery or distributed ACID.
- Keep the Webhook event ID/body immutable across attempts, exact-byte HMAC,
  HTTPS/no redirects/bounded HTTP, lease/attempt fencing, retained history and
  asynchronous DEAD retry semantics. Cross-event ordering is not promised.
- Preserve no-database-transaction-across-network-call behavior, including new
  DNS/proxy checks. Metrics/tracing do not justify changing locks/transaction
  boundaries or exposing another module's repositories/entities.
- Do not physically delete financial/public event/attempt history, reset
  PUBLISHED for convenience, blindly replay a DLQ or auto-fail UNKNOWN.

## 4. Metrics contract (T03-T04)

Use existing Micrometer infrastructure. Each owning module defines its metrics
at application/infrastructure boundaries; domain aggregates stay free of
telemetry dependencies. A shared observer must not query every module's private
repository or become a business orchestration service.

The metric catalog must specify name, unit, allowed labels, logical outcome
versus execution-attempt meaning, commit point and aggregation semantics.
Successful persisted transitions are observed after commit; rollback/replay/
equivalent redelivery cannot inflate logical success. Diagnostic attempts and
latency are distinct from persisted business outcomes.

Labels are bounded enums/route templates/outcome groups only. No merchant,
resource, request, trace, source-event or Idempotency IDs, URLs, authorization,
hashes, bodies, ciphertext or signatures become meter dimensions. Metrics are
not financial accounting and process-local counters are not durable truth.

Required coverage includes Payment/Refund outcomes and provider latency,
Idempotency acquisition/replay/conflict, Outbox backlog/age/publication failures,
Ledger posting/duplicates and Webhook due/DEAD/HTTP attempts/lease recovery.
Sample durable projections at a bounded cadence. Distinguish per-instance
gauges from shared-database values; never sum identical shared counts as though
they were disjoint. Sampling/export failure must not change business outcomes.

### P8-T03 metric catalog (frozen before instrumentation)

| Micrometer name | Type / unit | Exact bounded tags | Recording semantics |
|---|---|---|---|
| `flowpay.payment.confirmation.outcomes` | Counter / committed classifications | `outcome=SUCCESS,DECLINED,TECHNICAL_FAILURE,UNKNOWN` | After the existing finalization TX commits; no counter on rollback or HTTP replay |
| `flowpay.refund.outcomes` | Counter / committed classifications | Same four `outcome` values | After finalization TX commits; UNKNOWN is the first ambiguous observation per Refund, not a terminal failure |
| `flowpay.payment.provider.duration` | Timer / seconds in Prometheus | Above four `outcome` values plus `EXCEPTION` | One actual charge invocation, including thrown exceptions, even if later persistence fails |
| `flowpay.refund.provider.duration` | Timer / seconds in Prometheus | Above four `outcome` values plus `EXCEPTION` | One actual refund invocation; equivalent HTTP replay invokes no provider/timer |
| `flowpay.idempotency.decisions` | Counter / decision observations | `operation=PAYMENT_INTENT_CREATE,PAYMENT_INTENT_CONFIRM,REFUND_CREATE`; `decision=NEW,REPLAY,IN_PROGRESS,KEY_REUSED,NOT_FOUND` | Immediately once per returned acquire/findExisting decision, including conflicts in an outer TX that subsequently rolls back; NOT_FOUND is a lookup miss |
| `flowpay.idempotency.reservations` | Counter / committed new reservations | The same three `operation` values | Only NEW and only after the actual insert/outer TX commits; no counter on rollback/replay |

Counters use the standard Prometheus `_total` suffix. Timers expose seconds
count/sum/max and fixed SLO buckets at 10ms, 50ms, 100ms, 500ms, 1s, 5s, 10s and
30s (plus the backend's +Inf bucket); no client-side percentiles or automatic
percentile histogram. The catalog has at most 36 custom meter identities before
deployment-wide common tags; backend histogram series are an additional bounded
expansion. There are no provider-name, currency, amount, tenant, resource, error
code/message, request, Idempotency-key/hash or payload dimensions.

Owning application components pass only normalized enums into their meters.
A query-free shared recorder isolates registry registration/recording failures
and registers afterCommit callbacks on existing actual transactions. It never
creates a transaction/lock or records a committed counter outside an actual TX.
Provider timers use a monotonic process clock, not the registry/exporter, and
preserve the original result/exception. No raw diagnostic exception is logged.

Payment UNKNOWN terminates the confirmation attempt, not the PaymentIntent.
Refund remains PROCESSING on UNKNOWN; its existing persisted failure metadata
distinguishes the first observation, under the existing refund row lock. A later
terminal outcome may count separately; repeated UNKNOWN observations do not
inflate its logical counter. No new business state, storage or dedupe cache is
introduced. Decision counters are diagnostics, not numbers of accepted requests
or unique persisted executions; a reservation can later be safely released.
Logical outcomes refer to committed source facts, not HTTP success: a later
snapshot/Idempotency completion failure does not erase an already committed
outcome. Provider duration remains an attempt observation when finalization
subsequently rolls back. Unexpected provider exceptions get only the timer's
EXCEPTION label; they do not invent FAILED/UNKNOWN business facts. Meters are
registered lazily, so an unobserved label combination may have no series yet.

These are best-effort process-local counters: restarts/crashes/export failures
can lose observations, including the DB-commit-to-callback crash window. Sum
per-instance rates/increases for traffic/outcomes, and merge timer buckets for
latency distributions; do not average per-instance percentiles or derive money,
balances or exactly-once counts from metrics. The DB remains durable truth.
T04 owns durable sampling/query cost and async meters; no such query, migration
or index is authorized or introduced by T03.

The implementation uses existing Micrometer [timer/SLO bucket](https://docs.micrometer.io/micrometer/reference/concepts/timers.html)
and [registry recording/export semantics](https://docs.micrometer.io/micrometer/reference/concepts/meters.html),
with Spring's [afterCommit synchronization](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit()).
The catalog, transaction points, UNKNOWN dedupe and failure isolation are FlowPay
choices; they do not provide durable/exactly-once accounting.

### P8-T03 verification record

On 2026-10-04, Java21/Maven3.9.16 verification passed 179 focused tests in 15
classes and full `clean verify`: 1,256 tests in 176 suites, zero failures/errors/
skips, 5m29s. The focused gate covers the recorder/catalog, real PostgreSQL
commit/rollback and same-key concurrency, existing Idempotency/finalization
regressions, ArchUnit and transaction boundaries. All four normalized provider
outcomes, replay/conflict, repeated Refund UNKNOWN and thrown provider exceptions
are exercised; native Prometheus scraping verifies bounded labels/SLO buckets.

The run disables optional default OTLP export, except the owned business-metrics
fixture explicitly enables the real exporter against a disposable loopback HTTP
503 collector. A throwing registry fixture also verifies successful financial
operations, preserved original provider results/exceptions and safe diagnostics
when recording is unavailable. Required PostgreSQL/RabbitMQ suites ran without
skips; no development resources were reset. Compilation, architecture/transaction
checks and `git diff --check` pass; no formatting plugin is configured.

No dependency, schema, public API/event, financial state, locking or transaction
boundary changed. T04 async operational metrics and later deployment/tracing
gates are not implemented here. The task commit remains local to the phase
branch; local phase/index/agent documents and pre-existing `.env`/`.github/` are
excluded from task history.

### P8-T04 catalog and sampling decision (frozen before instrumentation)

| Name | Unit / bounded labels | Meaning and recording point |
|---|---|---|
| `flowpay.outbox.backlog` | Rows; `status=PENDING,FAILED` | Shared DB snapshot, includes future retry times |
| `flowpay.outbox.oldest.unpublished.age` | Seconds; no labels | Age since oldest unpublished created_at, zero when empty |
| `flowpay.outbox.publication.attempts` | Counter; `outcome=CONFIRMED,NEGATIVE_ACK,UNROUTABLE,CONFIRM_TIMEOUT,TRANSPORT_FAILURE,EXCEPTION` | Each relay execution's transport result, or envelope/transport exception; diagnostic, not committed publication |
| `flowpay.outbox.published` | Counter; no labels | After commit, only a successful conditional unpublished-to-PUBLISHED update |
| `flowpay.ledger.posting.decisions` | Counter; `type=PAYMENT_SUCCEEDED,REFUND_SUCCEEDED,REVERSAL`; `outcome=CREATED,DUPLICATE,CONFLICT,FAILURE` | Each repository posting claim's result/exception; not pre-claim provisioning or broker parse failures |
| `flowpay.ledger.postings` | Counter; same three `type` values | CREATED claim only after owning transaction commits, never equivalent duplicate |
| `flowpay.webhook.backlog` | Rows; `status=PENDING,RETRYING,DELIVERING,DEAD` | Shared DB snapshot, includes disabled endpoints and future schedules |
| `flowpay.webhook.oldest.due.age` | Seconds; no labels | Lateness since earliest due next_attempt_at for PENDING/RETRYING, including disabled endpoints; zero when empty |
| `flowpay.webhook.delivery.attempts` | Counter; no labels | After claim TX commits a new OPEN attempt; not proof HTTP was sent |
| `flowpay.webhook.http.outcomes` | Counter; `outcome=HTTP_1XX,HTTP_2XX,HTTP_3XX,HTTP_4XX,HTTP_5XX,CONNECT_TIMEOUT,REQUEST_TIMEOUT,TLS_FAILURE,TRANSPORT_FAILURE,INTERRUPTED,INVALID_URL` | Each worker send execution's normalized result, including pre-send secret/adapter failure; survives later failed/fenced finalization |
| `flowpay.webhook.delivered` | Counter; no labels | After fenced successful finalization commits; late/repeated result does not increment |
| `flowpay.webhook.lease.recoveries` | Counter; no labels | After a fenced expired lease and its OPEN attempt are recovered/abandoned and committed |
| `flowpay.metrics.sampling` | Counter; `component=OUTBOX,WEBHOOK`; `outcome=SUCCESS,FAILURE` | Projection executions, not skipped cadence calls |
| `flowpay.metrics.sampling.duration` | Timer / seconds; same two `component` values | End-to-end projection execution, including connection acquisition and failure; existing fixed SLO buckets |
| `flowpay.metrics.sample.age` | Seconds; same two `component` values | Monotonic elapsed time since last successful snapshot, NaN before first success |

Counters/timers are process-local best-effort observations; sum instance rates,
not financial amounts. Backlog/age gauges reflect the **same shared database**
on every replica. Select one fresh replica or aggregate with max (not sum);
replica snapshots are not atomic across instances or across the two regions.
Bounded labels only: no IDs, tenant, event type, URL, status code, exception,
payload or attempt number. Logical counters retain the commit/crash-window and
restart limitations of T03; persisted attempt/state history remains truth.

Sampling configuration: `flowpay.metrics.sampling.enabled` defaults true;
`fixed-delay` defaults 30s and is validated between 10s and 5m, inclusive.
The test profile disables autonomous sampling. Two isolated daemon scheduling threads use
their explicitly named scheduler, not the business-worker scheduler. Initial
delay equals the interval. Each owner also enforces monotonic minimum cadence
and single-flight, including failed/manual refresh calls: at most one query per
owner per interval per instance, no catch-up loops or scrape-time queries.

Each owning projection issues one read-only aggregate SQL statement, returning
one row of counts/minima, without explicit row locks, entities, cross-module joins or payload
materialization. Outbox restricts PENDING/FAILED; Webhook restricts the four
backlog states. Age uses database statement time, clamped non-negative.
Statement query timeout is 2s, but connection acquisition uses the existing
pool timeout and can take longer. Exact counts/minima are not constant-cost:
work scales with matching backlog rows and may scan retained terminal history
if PostgreSQL chooses a sequential scan. Existing status/schedule indexes may
help filtering but do not guarantee a plan. No new schema/index or production-capacity claim.
At default cadence two queries per instance per 30s; multi-instance DB cost
scales with replicas and must be measured at T14/T15 before operational claims.

Cache replacement is atomic after a successful projection. Before any successful
sample all backlog/age gauges are NaN; an empty successful sample is zero.
Failure retains the last successful snapshot and its growing sample age, emits
only a bounded FAILURE observation, and does not log sensitive causes or mutate
business state. Scraping reads cache only. Monitoring T08 must distinguish stale
or never-sampled gauges from healthy zeros; disabled sampling is not empty DB.
No financial transaction, locking/retry/lease/budget or API/event/schema changes.

Gauge lifetime/observation follows the existing [Micrometer gauge model](https://docs.micrometer.io/micrometer/reference/concepts/gauges.html);
explicit scheduling uses Spring's [named scheduler support](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/Scheduled.html#scheduler()).
Cadence, query timeout, cache semantics and aggregation above are FlowPay choices.
T04 adds at most 52 custom meter identities before deployment common tags and
the bounded timer series expansion. Cache refresh skips an ambient actual TX
before attempting a query; observation cannot join financial orchestration.
Failed gauge registration can leave a series absent until restart; missing,
never-sampled and stale metrics are not healthy zero-backlog evidence.

### P8-T04 verification record

On 2026-10-04, Java21/Maven3.9.16 final focused verification passed 186 tests in
19 classes (2m09s); full `clean verify` passed 1,287 tests in 180 suites, zero
failures/errors/skips, 5m31s. The focused command includes cache/catalog/config,
real PostgreSQL projection/outcome tests, Outbox Rabbit publication, Ledger
consumer retry/duplicate/concurrency/reversal, Webhook worker/lease/security
regressions and ArchUnit/transaction boundaries. Native Prometheus verifies
bounded labels/units and bucket expansion. Compilation/static checks and
`git diff --check` pass; no formatting plugin is configured.

Known fixtures prove counts/ages, future-time clamps, disabled-endpoint inclusion,
empty snapshots and two replicas reporting the same shared DB count. Repeated
refresh/scrape calls do not requery inside cadence; fake monotonic time proves
stale retention after failed sampling and no overlapping queries. An owned
PostgreSQL DDL-lock fixture verifies the actual 2s statement timeout. Callback
registration followed by beforeCommit failure proves publication, Webhook claim,
delivery and lease-recovery counters remain absent after rollback. Ledger
concurrent equivalent redelivery yields one committed posting; conflicts remain
diagnostic failures, not new postings. A late HTTP 2xx is observable as an attempt
but cannot inflate delivered after lease fencing. Throwing-registry fixtures
preserve actual published/posted/delivered state and original results/exceptions.

Default optional OTLP export is disabled for verification except the T03 owned
HTTP 503 collector fixture, which explicitly enables real export in the full
gate. All required PostgreSQL/RabbitMQ suites ran, no dev resource reset.
The named metrics scheduler is verified not to replace the default business
scheduler; disabled sampling removes only the metrics scheduler. No dependency,
migration/index, API/event/state, business transaction/locking or retry/lease
budget change. This is not measured staging/load/monitoring deployment evidence.
T05 was next and unstarted at the T04 checkpoint; task commit stays local on the phase branch.

## 5. Trace correlation (T05-T07)

### Asynchronous tracing policy (T07)

Reviewed before applying V013: add only nullable `webhook_events.traceparent`
VARCHAR(55), no default/backfill/index/business constraint. Capture the first
successful materialization span at INSERT; ON CONFLICT never replaces it, even
when equal redelivery has different/missing context. Rich domain rehydration,
public payload, event ID, signing bytes and merchant HTTP headers ignore it.
Webhook-owned infrastructure reads it after claim/before HTTP or before recovery,
outside application transactions; failed/missing/corrupt lookup starts a root.
The SQL statement has a two-second timeout (connection acquisition still follows
the existing pool budget). Legacy writers/readers remain compatible; V001-V012
checksums remain unchanged.

Single-message parent policy: Outbox publication attempts and independent Ledger
processing/Webhook materialization invocations are siblings under the validated
durable source creation context. Transport retains that original traceparent,
not a mutable last-publication span. Each retry/redelivery creates a fresh span;
no span includes broker backoff. Delivery/recovery spans are siblings under the
first persisted materialization context, including after application/SDK restart.
The existing `webhook.http` CLIENT span is a child of its delivery span. No links
are needed for this single-parent flow; max-links remains zero. This is a local
bounded causal policy, not a claim of full messaging semantic-convention coverage.

Only strict canonical version-00 sampled/unsampled nonzero lowercase W3C context
is accepted. Non-string/oversized/malformed headers are ignored; no baggage,
tracestate, B3 or ambient request/scheduler parent is inherited. Scopes close on
success/failure before reused threads handle other work. Telemetry availability
never repeats the callback or changes its result/exception, ACK, transaction,
dedupe, lease, retry policy, or financial result.

Exporter policy expands only by fixed names `outbox.publish` (PRODUCER),
`ledger.consume`/`webhook.materialize` (CONSUMER), `webhook.delivery` and
`webhook.recovery` (INTERNAL), and enum outcomes COMPLETED/EXCEPTION,
CONFIRMED/NOT_CONFIRMED, APPLIED/STALE. These are technical outcomes: APPLIED
means a guarded persistence transition, not payment/delivery success. No IDs,
attempt numbers, payloads, URLs, exceptions or secrets become attributes/events.
No span is open during delayed scheduling or across a held DB transaction and
HTTP call. Tracing remains diagnostic; disabled export/sampling is supported.

### Synchronous tracing policy (T05)

Reuse Boot 4.1.0's Micrometer/OpenTelemetry bridge and auto-configured SDK; do
not create a second SDK or servlet SERVER span. HTTP server observations retain
their existing metric tags, but omit high-cardinality URL attributes and use a
fixed span name. The export allow-list admits only HTTP method/status class and
normalized external-call outcomes. Existing framework/Security INTERNAL spans
remain present but carry no other export attributes. Span names are fixed (`http.server`,
`payment.provider`, `refund.provider`, `webhook.http`, or `internal`). Export
removes events, links, status descriptions, tracestate, arbitrary attributes,
and arbitrary resource/instrumentation attributes. Resource service.name is
`flowpay`. T07's reviewed expansion is specified above; any further expansion
requires another review.

Incoming propagation accepts W3C traceparent only, not baggage, tracestate or B3.
Malformed context starts a new trace. The parent-based ratio sampler defaults
to 10% for roots and honors sampled/unsampled parents; remote sampled input is
not an abuse/rate-limit control. IDs can correlate unsampled work without export.
X-Request-Id remains a separate validated client correlation value, not a span
attribute or sampling decision; callers must not put secrets in correlation IDs.
Boot's scoped MDC provides traceId/spanId; the console pattern includes them
beside requestId. Instrumentation never records/logs throwable messages or bodies.

Provider and Webhook calls use scoped CLIENT spans outside DB transactions,
preserve results/exceptions/interruption and restore context in finally. Webhook
HTTP uses the dedicated Webhook transport: no tracing/baggage/request-ID headers are
injected, and signed headers/body/timeouts/redirect rules remain unchanged.
This is synchronous tracing only; delayed jobs have no durable causality yet.

OTLP trace export is opt-in with `TRACING_EXPORT_ENABLED=true` and
`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` pointing to an operator-owned full HTTP
`/v1/traces` endpoint. Default disabled; no collector is required to run FlowPay.
`TRACING_SAMPLING_PROBABILITY` sets the root ratio (0..1). Boot properties are
`management.opentelemetry.tracing.export.otlp.*`, not the older Boot namespace.
The exporter enable switch is `management.tracing.export.otlp.enabled`.
Dev keeps sampling off by default; set `TRACING_SAMPLER=parent-based-trace-id-ratio`
to opt in (test remains always-off except explicit tracing fixtures).
Batch queue/batch limits are 2048/512, delay 5s, export timeout 3s; HTTP connect
timeout is 1s and request timeout 2s. SDK limits are 16 attributes/128 characters,
zero events/links. Async export can drop telemetry on outage/overflow and cannot
roll back a financial result. Exporter diagnostics are suppressed because third-
party logs can contain collector URLs/exception text; collector monitoring is
separate future work. Do not enable request/body/transport DEBUG logging in prod.

Reuse the installed tracing foundation and verify its actual Boot configuration.
Keep X-Request-Id, trace/span identity, business event identity and attempt
identity distinct. Never record arbitrary baggage, credentials, bodies, hashes,
ciphertext, signatures, sensitive exception text or unbounded user attributes.
In-memory test exporters verify context isolation and sensitive-data exclusion.
Collector outage must not fail a business operation.

### Durable Outbox metadata decision (T06, reviewed before DDL)

At the T06 design review the next unused migration was V012. It adds exactly
`outbox_events.traceparent VARCHAR(55) NULL`, with no default, backfill, index or
new business constraint. Existing V001-V011 checksums remain unchanged. Older
writers omit the column and existing rows remain NULL; older readers ignore it.
Length is bounded in PostgreSQL; format validation is fail-open in infrastructure
on capture, rehydration and Rabbit publication, not a constraint that can reject
an otherwise valid financial event. Noncanonical/malformed values become absent.

Accept only canonical lowercase W3C version 00 with nonzero 32-hex trace ID,
nonzero 16-hex span ID and flags 00/01. Persist the active source span identity
including unsampled context in the same transaction as the Outbox insert; no
active valid span means NULL. Never persist tracestate, baggage or request IDs.
Tracing capture failures are ignored without logging input/exception content.

Relay snapshots carry this technical value separately from the unchanged JSON
envelope/payload. The confirmed publisher adds only a validated `traceparent`
Rabbit header. Retry/restart reuse the stored source parent, not the ambient
scheduler/thread context. T06 does not activate a scope or create publication
spans, so there is no retained thread context or exporter-policy expansion.
T07 owns per-publication/consumer/worker spans and their parent/link decisions.
Trace metadata is not a business contradiction/dedupe fact and never enters
the public Webhook body/signature/headers. Delivery/confirm/backoff/TX behavior
remains unchanged, including when metadata is missing or corrupted.

T07 reviewed Webhook-owned V013 metadata for delayed workers as described above.
These are separate additive forward migrations using the next actually unused
versions, not reserved version numbers. Review concrete DDL, compatibility,
validation and test coverage before applying migrations; never edit applied
migrations or add trace fields to business payloads/public signed JSON.

Rabbit transport headers carry validated optional context. Legacy/absent/bad
context must not poison valid business processing or alter contradiction/dedupe
facts. T07 adds bounded producer, consumer, redelivery and worker spans with
the explicit single-parent policy above; do not hold a request span or DB
transaction open for retry delays. Clear context on reused threads. Outbound
merchant Webhooks do not inherit internal tracing headers without an explicit
separate contract review.

This is FlowPay's design choice for the durable boundary. Header validation and
planned span relationships follow [W3C Trace Context](https://www.w3.org/TR/trace-context/)
and the [OpenTelemetry messaging conventions](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/).
Importing those specifications does not upgrade Java dependencies automatically.

### T05 verification checkpoint (2026-10-04)

Final focused tracing/correlation gate: 22 tests in five classes, zero failures/
errors/skips, 1m06s. The earlier broader regression gate passed 249 tests in
19 classes (1m43s); the added provider-exception fixture was corrected and rerun
in the final focused gate. Full clean verify passed 1,307 tests in 184 suites,
zero failures/errors/skips, 5m53s. JDK 21.0.12/Maven 3.9.16, Boot 4.1.0,
Micrometer Tracing 1.7.0 and OTel 1.62.0; owned PostgreSQL 16.15/RabbitMQ 4.3
Testcontainers, not development resources. Optional default OTLP metrics export
was disabled on the command; explicit owned metric/trace outage fixtures enabled
real export. The packaged artifact includes the verified runtime tracing bridge;
no Java dependency or scope was added/promoted.

Coverage includes sampled/unsampled and malformed incoming context, configured
root ratios, concurrent isolation across 24 requests/four reused Tomcat threads,
MDC cleanup outside servlet filters, provider parent ancestry/replay/exception,
real committed Payment/Refund work during collector HTTP 503/throwing export,
missing collector connection refusal, and exact Webhook body/no internal headers.
In-memory exported SpanData and real OTLP payloads exclude synthetic sensitive
values; safe INFO/error logs preserve correlation without exception content.
Existing security/API/transaction, broker/migration and architecture regressions
pass. Compilation and diff/static checks pass; no format plugin is configured.

No API, persistence, financial state/event, transaction/locking/retry change or
external deployment. Sampling/queue loss and suppressed exporter diagnostics
are documented limitations, not durable accounting or operational monitoring.
At that checkpoint T06/T07 durable causality and staging/load/collector deployment were unstarted.
Only documentation was finalized after the unchanged successful full gate;
phase/index remain local-only and task commit is not pushed.

### T06 verification checkpoint (2026-10-04)

Final focused gates: 21 validation/migration/concurrent Rabbit cases in three
classes (46.7s), plus 27 metrics/Ledger regression cases in two classes (51.7s),
zero failures/errors/skips. Earlier broad 128-case coverage verified restart,
rollback, HTTP source capture, consumer compatibility and architecture; fixture
errors were corrected in the final focused gates. The first full run caught
older one-argument mock stubs and V011-only schema expectations; assertions
were retained and updated for the new transport signature/additive V012.
The final full `clean verify` passed 1,331 tests in 187 suites, zero failures/
errors/skips, 6m45s, and packaged `backend-0.0.1-SNAPSHOT.jar`.

JDK21.0.12/Maven3.9.16, Boot4.1.0, owned PostgreSQL16.15/RabbitMQ4.3
Testcontainers. Optional default OTLP metrics export was disabled; explicit
owned exporter-outage fixtures ran, and no required DB/broker tests were skipped.
Coverage includes fresh/upgrade and V001-V011 checksum/legacy-row preservation,
nullable/bounded metadata, source rollback, malformed/absent/sample flags,
16 source operations on four reused threads, 16 concurrent broker publications,
actual application close/restart, lost-confirm retry with identical bytes and
durable source headers despite unrelated ambient context, equal old/enriched
delivery and unchanged Ledger/Webhook effects. Compilation/ArchUnit/transaction
checks and diff/static checks pass; no format plugin is configured.

Only nullable technical storage and optional internal transport metadata changed;
no dependency, business API/V1 schema, state/locking/TX/retry or merchant HTTP
change. T05 export policy is unchanged; T07 publication/consumer/worker spans
and staging/collector/load evidence remain pending. Only documentation finalized
after the successful gate, so the unchanged full suite is not repeated. This
task is committed locally on the phase branch, not pushed; phase/index remain
local-only and `.env`/`.github/` are preserved.

### T07 verification checkpoint (2026-10-11)

Focused iteration/checkpoints verified 125 unique tests across 13 classes.
The broad checkpoint caught a concurrency-fixture assumption that candidate
discovery would assign one item per worker; actual SKIP LOCKED discovery is
advisory and can return a whole batch. The fixture was corrected without
changing production locks; its final two-test restart/concurrency rerun passed
with zero failures/errors/skips (59s). An earlier raw-message fixture omitted
mandatory messageId and was corrected, retaining validation assertions.

Full `clean verify` then passed 1,351 tests in 191 suites, zero failures/errors/
skips, 7m39s, and packaged `target/backend-0.0.1-SNAPSHOT.jar`. Environment:
Windows/JDK21.0.12/Maven3.9.16/Boot4.1.0 with owned PostgreSQL16.15/RabbitMQ4.3
Testcontainers and local HTTP receivers. Default optional OTLP metrics export
was disabled; explicit exporter-outage tests still ran. No required DB/broker
test was skipped. Compilation, ArchUnit, TX boundaries and git diff --check pass;
no formatter plugin is configured.

Evidence covers producer parent/unsampled/root behavior after app restart;
real Ledger retries, backoff-separated sibling spans, rollback and DLQ;
Webhook equal redelivery with different parents retaining first context;
non-string/bad/oversized Rabbit headers; fresh/upgrade/legacy/checksum compatibility;
actual app/pool/SDK close/restart; expired-lease recovery, stale result fencing,
real HTTP timeout and subsequent retry; exact immutable body/event ID/HMAC and
absence of internal merchant headers; simultaneous/reused worker-thread MDC/
context isolation and one HTTP invocation per committed claim; optional metadata/
SDK/exporter failure isolation. Existing full-suite financial/operational/E2E
regressions remain intact.

V013 contains only reviewed nullable Webhook-owned metadata. The shared helper
is persistence-free; domain/API/envelope/source facts, dependencies, retry/lease,
ACK, state, locking and financial transactions are unchanged. Five bounded names/
outcomes expand the export allow-list but events/links remain absent. Sampling,
queue loss and statement/pool lookup latency are diagnostic limits, not durable
accounting. No deployed monitoring/collector/Linux staging/performance evidence
is claimed; T08 is next/unstarted. Only docs finalized after the successful full
gate, so unchanged code is not reverified. Commit stays local on the phase branch;
phase/index/agent/env/user-owned .github paths remain excluded.

## 6. Production configuration and management (T02)

### Startup and secret ownership

P8-T02 registers an EnvironmentPostProcessor immediately after Boot ConfigData,
before bean binding/DB initialization. In `prod` it rejects missing/blank or
malformed mandatory settings with property names and fixed safe messages, not
values/parser causes. It requires a single-host PostgreSQL JDBC URL with a
database, separate username/password, and a RabbitMQ hostname/IP, valid port
and credentials. Credentials in the JDBC URL and Rabbit `addresses` overrides
are rejected; this baseline uses `host`/`port`, not a second connection path.

Prod cannot be combined with `dev`/`test` profiles. Ephemeral JWT generation,
insecure-localhost Webhooks and public Prometheus must be false; refresh cookies
must remain Secure. JWT resources must be readable X.509 public / PKCS#8 private
PEM containing a matching RSA pair of at least 2048 bits. Loading errors discard
sensitive resource/parser causes; persisted keys are reused, not regenerated.
The external Webhook encryption key must decode from standard Base64 to exactly
32 bytes. Do not use synthetic test keys outside tests.

Boot does not automatically read the root `.env`. Compose's `.env` substitution
does not export values into an IDE/application process; provide process env or
external ConfigData/secret mounts. Required environment names are `DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_USERNAME`,
`RABBITMQ_PASSWORD`, `JWT_PUBLIC_KEY_LOCATION`, `JWT_PRIVATE_KEY_LOCATION` and
`WEBHOOK_SECRET_ENCRYPTION_KEY`; `RABBITMQ_PORT` defaults to 5672. Use external
read-only mounts for persistent key files; never put secret values on a real
production command line, in an image, a Git commit or a diagnostics artifact.
Deployment operators own these secrets and continuity across replicas/restores.
Replacing the encryption key is not a supported rotation procedure and can
make existing endpoint records unreadable.

### Listener and authorization boundary

Prod management defaults to `127.0.0.1:9091`, independently of the business
listener (default 8080). `MANAGEMENT_SERVER_PORT` must be a different port;
port 0 is supported for isolated tests. `MANAGEMENT_SERVER_ADDRESS` defaults to
loopback. An explicit private interface/container bind is an operator decision:
do not publish that port, route it through public ingress, or attach untrusted
containers to its network. T13 must prove those actual deployment boundaries;
T02 does not claim that a staging network already exists.

A prod-only first security chain identifies the management connector by the
request's **local** port, not Host/Forwarded headers. It allows unauthenticated
GET/HEAD only for `/actuator/health/**`, `/actuator/info` and
`/actuator/prometheus`. The private listener/network is the scrape trust boundary;
this is not a management-user credential API. All other management routes,
including business APIs, discovery, env/configprops/loggers/shutdown, are denied.
On the business listener **all** `/actuator/**` routes are denied, even with a
valid dashboard JWT. Existing business authentication is unchanged. Dev retains
its existing single-listener local Prometheus behavior; test defaults stay closed.
Health details/components remain `never`; component navigation is hidden (404).

### Probes and lifecycle

| Prod route | Listener | Meaning |
|---|---|---|
| `/livez` | Business | `livenessState` only; process viability |
| `/readyz` | Business | `readinessState,db`; accepting traffic plus DB SQL connectivity |
| `/actuator/health/liveness`, `/actuator/health/readiness` | Management | Same groups, minimal status only |
| `/actuator/health` | Management | Aggregate dependency status, not routing readiness |
| `/actuator/prometheus` | Management | Prometheus scrape, no business authentication change |

DB outage makes readiness DOWN/503, not liveness. Broker outage makes aggregate
health DOWN/503 but leaves readiness/liveness UP/200 if PostgreSQL is usable;
durable source + Outbox acceptance remains available while publication/consumers
recover separately. Telemetry exporters are not readiness dependencies. The DB
SQL probe is not proof of write capacity, performance or successful asynchronous
delivery; schema validation/migrations remain startup checks. An explicit
REFUSING_TRAFFIC availability state makes readiness OUT_OF_SERVICE/503.

`server.shutdown=graceful` is retained and prod explicitly sets
`spring.lifecycle.timeout-per-shutdown-phase=30s`. Context closure drains an
in-flight HTTP request and stops new acceptance through Boot's server lifecycle.
This is a timeout **per lifecycle phase**, not a guaranteed whole-process 30s
deadline. T12 chooses a 120s container grace period and verifies image SIGTERM
and durable worker/source recovery. Those results are separate from this T02
HTTP-context test; multi-instance recovery remains T15. T12 now declares finite
image/runtime CPU/memory/JVM budgets; T14 still owns measured capacity/tuning.

The implementation follows Boot's [separate management listener](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html),
[health groups and additional probe paths](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html),
and [graceful shutdown lifecycle](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html).
FlowPay chooses DB-only dependency readiness to preserve its Outbox semantics.

T02 verification (2026-10-04, Java21/Maven3.9.16): the final focused regression
command passed 127 tests, followed by 45 expanded validator tests (41 overlap;
131 distinct cases). Full `clean verify` passed 1,227 tests across 173 suites in
5m16s, zero failures/errors/skips. Optional local OTLP metrics export was disabled;
DB/broker tests were not skipped. Real prod-profile listeners use owned disposable
PostgreSQL/RabbitMQ plus temporary synthetic key files. Tests cover sanitized
startup errors, persistent/weak/mismatched/malformed/unreadable RSA keys, public
denial with valid JWT and forwarded headers, minimal probes, dependency outages,
explicit REFUSING_TRAFFIC and in-flight HTTP drain. Existing source/Outbox
broker-outage recovery, authentication and architecture/transaction gates pass.
An executable-JAR smoke check verifies the packaged guard rejects prod/dev
profiles before context initialization (exit 1, expected sanitized message);
it does not access development infrastructure or claim deployment readiness.

## 7. Staging topology and monitoring (T08/T12-T13)

P8-T13 supplies the independent definition in `ops/staging/compose.yaml` and
[staging deployment runbook](staging-deployment.md). It reuses the T08 dashboards/
rules and T09/T10 policies, not the development volumes or the T08 JAR-mount lab.
Only loopback TLS ingress is published; all management/monitoring remains private.
The bootstrap verifies all source bindings and separate consumer DLQs before
producers, using a non-admin application broker identity. Fresh/V011 upgrade,
key continuity, native dual-stack boundaries and same-image configuration
rollback are separate evidence from security release acceptance. Existing
source/image findings still block release; no external deployment is authorized.

Linux containers are the chosen lab target. Use a separate staging definition
with independently named volumes/networks and no fixed dev container names.
Public ingress goes through the reviewed TLS edge; PostgreSQL, RabbitMQ,
management and collectors remain private. Choose and pin compatible image
versions/digests at implementation, not floating `latest` images in a release.
The lab must support at least two backend instances without naming conflicts.

P8-T08 implements the independent simulator lab in `ops/monitoring/compose.yaml`,
not the T12 production image or T13 staging deployment. Its four monitoring
images are Prometheus 3.15.0, Grafana 13.2.3, core OTLP Collector 0.162.0 and
Tempo 2.10.4; all eight lab/check images pin an exact version **and manifest
digest**. The latter is a maintained Tempo 2.x branch. Actual configuration
validation/startup and OTLP/Prometheus/Grafana compatibility are tested; this is
not a vulnerability scan or approval to deploy those images. T11/T12 own review,
SBOM and image security/upgrade evidence.

The existing verified executable JAR is mounted read-only in a non-root stock
JRE21 container with `prod`, dedicated management and external ConfigTree/secrets.
No Java/library, application-default configuration, API/event/schema, financial
state, transaction/locking/retry change. Disposable simulator secret files are
generated outside the repo with a protected host directory; each service receives
only its required files. Root `.env` and user-owned `.github/` are not consumed or
adopted. Do not promote synthetic fixture keys to a retained staging deployment.

No fixed container names, external volumes/networks or public DB/broker/collector/
management/business ports. Only authenticated Grafana is published on loopback
3300 (overridable); it alone also joins the project-local UI bridge, since Docker
does not publish internal-network-only ports. Other services use one internal
network without external egress. That network is the unauthenticated telemetry
trust boundary, not TLS/public-ingress or effective destination enforcement.
The UI bridge is not an egress firewall; T09/T13 retain their explicit gates.

Versioned provisioning creates the two datasources and 21 operational panels:
API mean latency/rates (no existing server histogram), fixed-bucket provider p95,
committed outcomes/Idempotency decisions, fresh shared-DB backlog/ages, required
queue consumers/DLQ, Webhook attempt/recovery, JVM/pool/CPU/GC and telemetry health.
Process-local counter rates are summed; shared DB gauges use max only over
snapshots <=120s old with the default 30s sampler, never sum or substitute zeros
for stale/NaN/missing samples. Native RabbitMQ detailed metrics are restricted to
the two event queues/two DLQs. A reviewed cadence change requires freshness
threshold review, not an automatic claim of healthy cached data.

Prometheus scrape/evaluation 15s, timeout 5s; metrics retention 48h/256MB blocks
(WAL/head add space). Tempo local-block retention 24h and per-trace limit 256KiB;
time retention is not a hard disk quota. Collector: 160MiB limiter/32MiB spike,
256 queued batches, maximum batch 512 spans, 2s export timeout/30s retry budget,
no payload/debug/log exporter. Finite per-container memory/CPU budgets are lab
guardrails, not measured production capacity or universal performance SLOs.

Twelve evaluated alerts have a condition, hold/window, severity, explicit recovery
evidence and tested runbook anchor. Provisional operational thresholds and rate
floors are recorded in [the monitoring runbook](monitoring-runbook.md); T14 owns
performance-derived recalibration. The lab has no external paging receiver/HA
monitor; Prometheus cannot alert on its own complete outage. Disappearance alone
is not recovery when telemetry is missing. Collector/scrape loss may discard
spans/observations but cannot change financial outcomes or durable source history.

Recreate/config/fixtures/controlled E2E/outage commands, topology/retention limits,
operational recovery guidance and official compatibility sources are in the
runbook. T08 does not provision a vendor account, build the production image,
deploy TLS staging, implement egress enforcement or start T09.

### T08 verification checkpoint (2026-10-11)

Final independent clean Compose acceptance passed config/static/image/secret/
private-topology/runbook coverage, Collector validation, promtool 16 rules and
14 scenarios/28 assertions (every alert fires/resolves; NaN and shared-replica
max/stale exclusion are covered). Actual provisioned Grafana JSON/datasources,
all dashboard targets, native business/queue/cache metrics and stored controlled
HTTP/provider/Outbox/Ledger/Webhook-materialization trace/proxy lookup pass.
Trace storage excludes the synthetic credential/PII/body markers. Loopback UI
access is tested on the host. All four monitoring services are stopped, financial
operations/replay/conflict still pass, monitoring restarts and a new trace is
retrievable. The read-only SQL audit verifies three partially refunded payments,
three successful refunds, six unique Ledger postings/twelve balanced entries.

Focused application regressions passed 15 tests/four classes (1m56s), zero
failures/errors/skips: business/operational metric catalogs, real prod management
and HTTP tracing/export outage tests with owned PostgreSQL/RabbitMQ. Shell syntax,
Compose/provisioning checks and `git diff --check` pass; no formatter configured.
Added scoped LF Git attributes keep the Linux shell fixtures runnable after
Windows checkout. Earlier disposable iterations corrected tmpfs YAML quoting,
the pinned Tempo override field, Payment confirmation meter name and Grafana
read-only provisioning/proxy startup waits; no financial code was changed.

No full application `clean verify` rerun for T08: Java/application-default/shared
Spring wiring/security/schema/transaction/messaging behavior are unchanged. The
mounted JAR is the unchanged T07 full-gate artifact, not a new full-suite claim:
`backend-0.0.1-SNAPSHOT.jar`, 77,206,176 bytes, SHA256
`69866b637f5dd9305f7378df59efb45f7b218299436059d46c41d91d151650b9`.
Host: Windows + Git Bash/Compose 5.3.1, Docker Engine 29.6.2 Linux x86_64,
4 CPUs/3,920,216,064 bytes engine memory. Lab: Temurin JRE21.0.10_7,
PostgreSQL16.15/RabbitMQ4.3.0 and the four pinned monitoring versions above;
Java focused gate: JDK21.0.12/Maven3.9.16/Boot4.1.0. This is not a native Linux
host/orchestrator, two-instance, sustained-load or staging acceptance run.

Only this task's disposable project/network/volumes were removed; fixture DB/
telemetry contents are irrecoverable, development resources untouched. External
temporary synthetic secret files remain for manual deletion (no values logged).
Tracked source-of-truth docs synchronized; phase/index corrected locally and
never staged. Branch feature/phase-8-production-engineering, local task commit
only, no push/merge. External paging/HA, security scanning, production image/TLS
staging and performance/recovery evidence remain later gates. At that T08
checkpoint, T09 was unstarted; its acceptance is recorded in section 8 below.

T12 provides an artifact-first equivalent to a multi-stage build: the exact
clean-verified JAR in a pinned Java21 JRE image. Non-root Java PID1, no embedded
local secrets/source, finite JVM/cgroup/temp budgets, read-only execution and
isolated stop/restart/API/Ledger/Webhook checks are described in
[container runtime](container-runtime.md). Source, dependency and image
checks/SBOM remain separate release evidence from functional tests.
Provision all six queue bindings and separate consumer DLQs before enabling the
source release. Test fresh startup, forward upgrade, shutdown/restart and
application rollback compatibility in T13; never auto-roll back database migrations.

Using a distinct production configuration is consistent with [Docker's Compose
deployment guidance](https://docs.docker.com/compose/how-tos/production/).
This project chooses an isolated lab; it has not deployed onto an external host.

## 8. Egress and security threat model (T09-T10)

Untrusted inputs include tenant requests/headers, Webhook URLs/DNS, merchant
responses, broker messages/redelivery and trace context. Protect credentials,
tenant financial records, immutable event facts and Ledger/Idempotency state.

T09's first-choice design is a dedicated enforcing egress proxy/network path
with the current JDK HTTP client. Verify all effective IPv4/IPv6 destinations,
block private/local/link-local/metadata/reserved targets and mixed DNS answers,
retain hostname/SNI/certificate validation and prevent direct-path bypass.
The actual connecting component must enforce the approved destination; a DNS
lookup followed by an independently resolving hostname request is insufficient.
Application checks remain defense in depth. DB/broker/telemetry private access
is separately allowed; indiscriminate private-egress denial is not the design.

T09 selects a concrete proxy through a reproducible security/compatibility spike,
pins its image and proves rebinding/non-bypassability with isolated fixtures.
If the JDK/proxy approach cannot pass, stop and review the required alternative
or dependency. Do not silently ship lookup-only protection or disable TLS.
This defense-in-depth requirement is informed by [OWASP's SSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

P8-T09 transport review (2026-10-11): the proxied JDK client failed accepted
public IPv6-literal TLS/SNI on Temurin 21.0.10+7, 21.0.12.1+1 and the authorized
25.0.4.1+1 spike. Those rejected spikes are historical evidence, not acceptance.
The user then approved Apache HttpClient 5 for Webhook only; Java 21 and all
public financial/event/signing/transaction contracts stay unchanged.

The replacement uses Client 5.6.4/Core 5.4.4, with documented async TLS/Core
fixes motivating the BOM override. It remains behind `WebhookHttpClientPort`;
application/domain code cannot import Apache HTTP APIs. The fixed CONNECT route
has no system-proxy/direct fallback. Explicit trusted TLS and hostname/IP-SAN
verification, HTTP/1, finite pool/I/O bounds, no cookies/auth/compression,
redirects or transport retries preserve the existing delivery semantics.
Signing and pool/CONNECT/TLS/header waits share a monotonic deadline; unread
bodies are hard-cancelled at headers, not buffered. Plaintext CONNECT refusals
are normalized transport failures, not merchant acknowledgements.

Only trusted operator proxy DNS is resolved at bounded initialization, then
snapshotted. Restart the client/application after proxy address changes. Slow
startup DNS fails closed; merchant destination resolution/enforcement remains
in the reviewed Squid 6.13 proxy. Its pinned image, all-answer ACLs, warning-free
startup, non-root/read-only limits and internal-app/outbound-proxy topology are
recorded in [Webhook egress design and evidence](webhook-egress.md).

P8-T09 completion: final focused **205 tests/nine classes** (44.165s), full
**1,388 tests/193 suites** (8m37s), zero failures/errors/skips, real PostgreSQL/
Rabbit. Expanded final-JAR Linux DNS/TLS/IP-SAN/HMAC/bypass fixture passes all
**34** independent assertions, including required public IPv6-literal delivery;
no assertion or TLS check was removed. Affected monitoring smoke passes native
prod/proxy startup, ingestion/provisioning, telemetry outage/recovery and intact
financial/replay/conflict/balanced Ledger checks on that same JAR/Java 21 runtime.
The egress record contains its digest/resources and owned-resource cleanup proof.
Repository-wide scanner/image/staging acceptance remains T11-T13 work, not a
claim made by source review or this isolated compatibility spike. T10 is next
at the T09 checkpoint; no push/merge or external deployment is claimed.

T10 reviews auth/refresh-cookie origin/CSRF, CORS, tenant isolation, management,
trusted-proxy headers, abuse controls and log/error redaction. Rate limits,
429/Problem Details/Retry-After changes or a token/auth contract correction need
documented evidence and explicit contract review before implementation. Prefer
the selected edge/current stack; Redis/new modules are not preapproved.

T10 review started 2026-10-13 on `1e27eb8`: cookie-authenticated refresh/logout
have no CSRF/Origin barrier. Two same-site sibling form-POST regressions reproduce
200/204 instead of required denial; existing 11 auth cases pass. The documented
threat/preconditions and proposals in [API security review](api-security-review.md)
received explicit owner approval of the auth request marker and edge/rate-limit
response contract. The chain-local decoded-path filter now requires exactly one
`X-FlowPay-CSRF: 1` on auth POSTs and denies auth CORS preflight before credential/
session work. Non-browser clients and legitimate tests/monitoring fixtures are
updated explicitly, without bypassing the guard. Bearer APIs, financial state,
schema, token TTL/cookie policy and provider/transaction behavior remain unchanged.

Approved NGINX 1.30.5, pinned by digest, uses finite single-edge auth 10/minute/IP
burst 5 and mutation 10/second/IP burst 20 budgets. Incoming forwarded/rewrite
headers are stripped, Host/request ID overwritten, and prod app forwarding is
`none`. Immediate safe correlated 429/Retry-After responses precede upstream work;
no upstream mutation retry or 429 interception occurs. Native owned HTTP policy
and real-app/DB/broker fixtures verify acceptance separately; they are not T13
TLS staging or supply-chain/performance proof. Final 266-test focused checkpoint
and full 1,405-test/195-suite clean verify pass, zero failures/errors/skips. Native
policy and real-app/DB/broker ingress gates verify limits, spoofing, non-forwarding,
replays/refill and unchanged balanced unique financial state. Affected monitoring
outage/recovery and all 34 Webhook DNS/TLS/HMAC/bypass assertions pass on the same
JAR; artifact/resource/owned cleanup evidence is recorded in the security review.
No finding was silently waived; at that T10 checkpoint, T11 was unstarted. Reviewed contracts,
version rationale, reproducer, artifact and limitation details are in the
tracked security review rather than inferred from a green unit suite.

Required positive and negative tests cover cross-tenant/auth failures,
management exposure, header spoofing, DNS rebinding/redirect/bypass, invalid TLS,
secret/cardinality/context leakage, collector outage and financial isolation.
Do not attack external services or send load to real merchants/providers.

## 9. CI and artifact authority (T11-T12)

The pre-existing `.github/` directory was user-owned/untracked at phase entry.
T11 reviewed its sole workflow and received explicit user confirmation on
2026-10-15 before adoption. `.env` and local-only agent/phase/index files remain
excluded. This is not authority to publish, dispatch CI remotely or deploy.

CI must execute full JDK21 verification with required PostgreSQL/RabbitMQ suites
and reject missing/skipped required infrastructure coverage. Introduce pinned,
least-permission dependency/SAST/secret checks and safe SBOM artifacts under a
documented severity/exception policy. Scanner unavailability is not a clean
scan. Do not mechanically upgrade dependencies or leak sensitive test output.
Building/scanning an image is not permission to upload it or deploy a release.

T11 implements independent `verify` and `supply-chain` jobs with SHA-pinned
checkout/setup/upload actions, read-only repository credentials, no persisted
checkout token and no shared PR-writable dependency/DB cache. JDK21 preflight
checks both PATH Java and Maven's actual JVM. Full clean verification is checked
against every concrete source suite; missing/stale/empty/skipped reports fail.
Pinned CycloneDX/Grype/Gitleaks/local-rule Semgrep gates use native negative and
clean controls, fresh integrity-checked advisory data and explicit safe artifact
projections. A scan/tool/network failure is not a clean result.

The first tracked-source scan found 10 baseline secret-format detections at seven
locations in five unchanged files (public API-prefix/Idempotency examples and
synthetic test encryption keys). Only rule/path/line metadata was inspected,
not published matches. The user explicitly chose **no exceptions; leave the gate
blocking and review later**. `exceptions.json` stays empty, correct fixtures and
detectors stay unchanged, and no clean security/release acceptance is claimed.
Detailed tool scope, severity/30-day exception policy, findings, local verification
and remote-CI limitations are in [CI supply-chain gates](ci-supply-chain.md).

Final local controls pass: 28 focused gate tests on both Windows and Linux,
native actionlint, source and dependency negative/clean/unavailable-DB controls,
and full JDK21 Maven verification with 1,405 tests/195 suites, zero failure/error/
skip and actual PostgreSQL/RabbitMQ. Grype scans 234 components against valid
DB v6.1.10 built 2026-10-15T06:30:36Z and reports 29 candidate matches (6 CRITICAL,
11 HIGH, 11 MEDIUM, 1 LOW). The 17 HIGH/CRITICAL findings also block; no dependency
waiver/upgrade or exploitability clearance is invented. No POM, app source or
test fixture changed. GitHub-hosted CI/branch protection remain unexecuted owner
gates on this unpublished branch, not inferred from local verification.

### T12 implementation/runtime checkpoint (2026-10-15)

T12 implementation/runtime checkpoint:55 focused Windows/Linux controls and
full1,405-test/195-suite Java clean verification pass, no failures/errors/skips.
Actual image prod/restart/API/Ledger/Webhook/outage/key-continuity gates pass.
Image inventory covers299 software components;18 HIGH/CRITICAL candidate
matches remain unwaived and block acceptance. Image application resources have
zero secret findings, but source retains10 baseline detections and0 SAST
findings. Native scanner/DB-unavailable controls pass; security is **BLOCKED**,
not a scanner outage or approval to deploy. Exact artifact/tool/resource/scan
evidence and limitations are in [container runtime](container-runtime.md).
No runtime application dependency, financial schema/API/TX/retry change.

## 10. Performance, recovery and operational gates (T14-T17)

No numeric universal RPS/p95, production availability or zero-loss guarantee is
selected at T01. T13 records artifact/image/tool/host/resource identity. T14
separates exploratory calibration from an independent acceptance run: record
and approve workload/concurrency/duration/error/latency/backlog-drain thresholds
before that acceptance run, then keep them fixed. Expected 202/409/business
outcomes are classified separately from unexpected server failures.

Exercise steady/peak/stress/soak with simulator data and controlled receivers.
Check refund capacity, Ledger balance/uniqueness, Idempotency and immutable event
identity after each scenario. Tune only from evidence under separate reviewed
scope; do not reduce production retry/jitter policies to manufacture a pass.

T15 tests at least two app instances, broker/DB interruption, process death,
stale/slow HTTP, rolling restart and monitoring outage. Financial snapshots and
eventual recovery evidence accompany durations; a timeout is not automatically
a known provider failure. External HTTP duplicates remain possible.

T16 freezes RPO/RTO/cadence/confidentiality/retention targets at task entry before
an acceptance restore drill. Restore into a separately named validated target.
Preserve consistent PostgreSQL snapshots, broker definitions/durable state and
separately protected encryption/JWT keys. A database dump alone cannot prove
recovery. PUBLISHED does not imply completed Ledger/Webhook consumption;
DB/broker checkpoint mismatch needs a reviewed controlled recovery procedure
using original facts/event IDs and explicit operator authority, never blind
replay/state reset or provider reconciliation.

T17's runbook reflects actual alert/load/failure/restore evidence. Separate
read-only triage, approved reversible actions and irreversible operations.
UNKNOWN is investigation/escalation, not permission to release refund capacity
or mark terminal failure. No deletion of financial/event/attempt history or
automatic DLQ replay is added under a documentation task.

## 11. Implementation decision ownership

| Gate | Owner | Must be resolved before |
|---|---|---|
| Internal management listener/access and probe semantics | T02 | Prod configuration/security implementation |
| Metric names/units/tag allow-list/commit/aggregation/sampling | T03-T04 | Registering meters/projections |
| HTTP sampling/attributes/context cleanup | T05 | Instrumentation |
| Concrete nullable trace DDL and compatibility | T06-T07 | New forward migration or transport/storage change |
| Compatible monitoring/trace images and retention | T08 | Provisioning/scanning the lab stack |
| Concrete egress proxy and effective-destination/bypass proof | T09 | Shipping outbound-network enforcement |
| Auth/abuse findings and any API behavior change | T10 | Contract/security change |
| Existing CI ownership/adoption and scan exception policy | T11 | Staging user-owned workflow files |
| Runtime/staging manifest resources and upgrade compatibility | T12-T13 | Image/staging acceptance |
| Numeric performance targets and declared workload | T14 | Independent load/soak acceptance run |
| Recovery targets, backup security and checkpoint procedure | T16 | Backup/restore acceptance drill |

These are bounded implementation gates, not unresolved financial architecture
or blanket authority to add libraries/schema/API changes. A task stops for
review if its chosen implementation exceeds the recorded scope. T01 can finish
without pretending to know measurements or tool versions that later gates own.

## 12. Verification and completion policy

Use focused unit/integration checks during iteration and one full gate at a
task checkpoint when security/configuration/persistence/messaging/wiring or
concurrency changes. T01 and T18 require a full baseline/final gate. Do not
repeat an unchanged full Java suite merely because documentation was edited.
Keep PostgreSQL/RabbitMQ Testcontainers, not H2, and reject skipped required
infrastructure suites.

Image/security/CI, real network/staging, load/soak, multi-instance failures and
actual restore drills provide distinct artifact-bound evidence. Record date,
source/image identity, resource/tool versions, commands/expected outcomes,
results/limits and safe cleanup; keep secrets and local-only artifacts out of
history. Existing dev volumes and pre-existing files remain untouched.

Phase 8 is DONE/FROZEN only after T18 verifies all required outcomes and residual
risks for the selected lab. That does not authorize cloud deployment, guarantee
exactly-once/ordered delivery, imply real-money readiness or permit a next phase.
