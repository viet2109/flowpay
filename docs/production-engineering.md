# Production Engineering — Phase 8 Design Record

Status: P8-T01 design/exit baseline complete on 2026-10-04; Phase 8 IN PROGRESS.
Runtime hardening is not implemented by this document. Phase 7 remains
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
| Source events | Six processing/success/known-failure facts; success V1 preserved; UNKNOWN non-terminal | T06-T07 add technical trace correlation, not business fields |
| Webhook delivery | Immutable event/subscription snapshot; claim/OPEN/lease; exact-body signed no-TX HTTP; fenced finalize; jitter/DEAD/manual retry | T04/T07/T09/T14-T16 operational evidence |
| Observability foundation | Actuator, Micrometer Prometheus registry, OpenTelemetry starter, X-Request-Id MDC | T03-T08 add and verify business/async observations |
| Management security | Prod does not permit Prometheus; current catch-all denies access | T02 supplies a tested internal scrape route, never a public workaround |
| URL/network safety | HTTPS/literal target checks, no redirects, bounded JDK HTTP; arbitrary DNS hostnames are not resolved by URL policy | T09 proves effective-destination and egress non-bypassability |
| Health/shutdown | Probes and graceful shutdown configured; workload-specific readiness not yet verified | T02/T12-T15 prove semantics and lifecycle |
| CI | A workflow exists locally in untracked `.github/`; it is not the repository's committed CI | T11 reviews ownership and introduces approved tracked gates |
| Packaging/operations | No production image/staging/monitoring/load/restore gate demonstrated | T08/T12-T18 own implementation and evidence |

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

T03/T04 freeze exact meter names, labels and sampling/query costs before code.
No migration/index is implicitly authorized by the metrics scope.

## 5. Trace correlation (T05-T07)

Reuse the installed tracing foundation and verify its actual Boot configuration.
Keep X-Request-Id, trace/span identity, business event identity and attempt
identity distinct. Never record arbitrary baggage, credentials, bodies, hashes,
ciphertext, signatures, sensitive exception text or unbounded user attributes.
In-memory test exporters verify context isolation and sensitive-data exclusion.
Collector outage must not fail a business operation.

For restart-stable asynchronous causality, T06 reviews bounded nullable W3C
trace-context fields owned by Outbox and writes them atomically with the source
transaction. T07 reviews equivalent Webhook-owned technical storage if needed
for delayed workers. These are separate additive forward migrations using the
next actually unused versions, not reserved version numbers. Review concrete
DDL, compatibility, validation and test coverage before applying them; never
edit V001-V011 or add trace fields to business payloads/public signed JSON.

Rabbit transport headers carry validated optional context. Legacy/absent/bad
context must not poison valid business processing or alter contradiction/dedupe
facts. Producer, consumer, redelivery and worker attempts get bounded spans and
explicit causal links/parent relationships; do not hold a request span or DB
transaction open for retry delays. Clear context on reused threads. Outbound
merchant Webhooks do not inherit internal tracing headers without an explicit
separate contract review.

This is FlowPay's design choice for the durable boundary. Header validation and
span relationships are implemented against [W3C Trace Context](https://www.w3.org/TR/trace-context/)
and the [OpenTelemetry messaging conventions](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/).
Importing those specifications does not upgrade Java dependencies automatically.

## 6. Production configuration and management (T02)

Fail startup safely for missing/invalid mandatory DB/broker configuration,
persistent JWT key material or the externally supplied 32-byte Webhook
encryption key. Do not expose values in diagnostics. Development ephemeral JWT
and insecure-localhost conveniences must not silently activate in prod.
Replacing the encryption key is not a supported rotation procedure and can
make existing endpoint records unreadable.

Provide a dedicated management listener reachable only on the lab's internal
management path, not through public/business ingress. Prove internal scraping
works and public scraping does not. Do not simply enable
`prometheus-public=true` or treat exposure configuration as authorization.
T02 documents the exact listener/access enforcement and tests the real routes;
T13 proves the staged network/reverse-proxy boundary.

Liveness represents process viability. Readiness represents the declared
request workload. DB inability to accept source transactions matters; broker or
collector failure must not automatically block otherwise valid durable source
requests merely to make a global health endpoint green. The exact health-group
behavior is reviewed/tested in T02 and remains distinct from dependency alerts.

Document application versus Compose environment loading, configured resource
limits, graceful shutdown and required secret continuity. Never commit `.env`,
JWT/private key material or Webhook master keys.

## 7. Staging topology and monitoring (T08/T12-T13)

Linux containers are the chosen lab target. Use a separate staging definition
with independently named volumes/networks and no fixed dev container names.
Public ingress goes through the reviewed TLS edge; PostgreSQL, RabbitMQ,
management and collectors remain private. Choose and pin compatible image
versions/digests at implementation, not floating `latest` images in a release.
The lab must support at least two backend instances without naming conflicts.

The monitoring baseline is local Prometheus/Grafana with an OTLP collector and
local trace backend; T08 chooses the exact trace backend and compatible images
after a focused compatibility check. This adds no business runtime Java library
or vendor account in T01. Provision dashboards/alerts from files and supply
credentials externally. Telemetry is off the financial critical path.

The non-root runtime image has a correct signal/entrypoint path, no embedded
secrets and declared writable temp/read-only/resource requirements. Source,
dependency and image checks/SBOM are separate evidence from functional tests.
Provision all six queue bindings and separate consumer DLQs before enabling the
source release. Test fresh startup, forward upgrade, shutdown/restart and
application rollback compatibility; never auto-roll back database migrations.

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

T10 reviews auth/refresh-cookie origin/CSRF, CORS, tenant isolation, management,
trusted-proxy headers, abuse controls and log/error redaction. Rate limits,
429/Problem Details/Retry-After changes or a token/auth contract correction need
documented evidence and explicit contract review before implementation. Prefer
the selected edge/current stack; Redis/new modules are not preapproved.

Required positive and negative tests cover cross-tenant/auth failures,
management exposure, header spoofing, DNS rebinding/redirect/bypass, invalid TLS,
secret/cardinality/context leakage, collector outage and financial isolation.
Do not attack external services or send load to real merchants/providers.

## 9. CI and artifact authority (T11-T12)

The pre-existing `.github/` directory is user-owned/untracked at entry. It is
not adopted by this task. T11 must review the current contents/ownership and
obtain confirmation before committing or overwriting them.

CI must execute full JDK21 verification with required PostgreSQL/RabbitMQ suites
and reject missing/skipped required infrastructure coverage. Introduce pinned,
least-permission dependency/SAST/secret checks and safe SBOM artifacts under a
documented severity/exception policy. Scanner unavailability is not a clean
scan. Do not mechanically upgrade dependencies or leak sensitive test output.
Building/scanning an image is not permission to upload it or deploy a release.

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
