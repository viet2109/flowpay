# Monitoring lab and alert runbook (P8-T08)

For the current staging incident/approval workflow and release blockers, use
the [operational runbook](operations-runbook.md) and
[release checklist](release-checklist.md). The3300 Grafana access below belongs
only to this older monitoring lab; T13 staging keeps Grafana/private management
unpublished. Alert predicates/anchors in this document remain authoritative.

P8-T18 aligns this lab's existing Java/certificate helpers with the reviewed
Java21.0.12.1/Jammy application base from the
[security remediation](dependency-security-review.md). No monitoring scenario,
resource/timeout/alert threshold or network policy is changed. The historical
measurements below remain historical until the final candidate's native
monitoring/alert-rule rehearsal is recorded; a version-reference update alone
is not acceptance or a scan of every monitoring service image.

Final repetition on2026-10-18 now PASSes the unchanged native script using the
verified candidate JAR and reviewed pinned JRE: Compose/policy validation,
promtool config/rule fixtures, Collector validation, all provisioned dashboards/
queries/metrics/stored trace, real monitoring outage/business continuation and
fresh recovery scrapes, plus six unique balanced financial postings. The other
egress/real-app-ingress scripts also pass in the sequential invocation
03:13:06.323628Z–03:17:23.929491Z. Safe aggregate report SHA-256:
`82c290dabdf6f40695c7bc4a59db15bef68784ecdf8ed469f421fda98822b262`.
All owned resources and external synthetic temporary keys were removed, absence
verified. The monitoring script's LF-normalized SHA-256 is
`39f6bd1ceb6380433f81b596d06ccaf599539e0271d9886db5c30a986090a26d`.
This uses the JAR/base helpers, not a second application-image build or scan;
no external alert notification or public deployment is established.

Native smoke preflight now rejects pre-existing project containers, networks
and volumes, and aborts if any inventory command fails. Assignment-based checks
preserve the command's nonzero status instead of treating failed/empty output
as absence. Offline fake-Docker regressions exercise existing/error cases for
all three resource kinds before any fixture creation; they are not a native
monitoring acceptance or real Docker interaction.

This is an isolated **simulator lab**, not a deployment/release gate or financial
source of truth. Use the built and verified JAR; no new application image is built
here. Never reuse the development Compose file, its container names or volumes.

P8-T09 adds a non-root enforcing egress proxy and required prod proxy settings.
Only that proxy joins a separate outbound bridge; app/DB/broker/telemetry remain
on the internal network. The user approved dedicated Apache Webhook transport
after rejected JDK IPv6-literal SNI spikes; the lab app remains on Java 21.
Proxy DNS is snapshotted at bounded startup: restart the application after
changing proxy addresses, never configure a merchant-derived proxy. See
[egress design and evidence](webhook-egress.md). Final T09 monitoring smoke passes
on the same verified JAR as the separate expanded network fixture. The original
T08 evidence remains historical; see the T09 rerun record below.

## Recreate and verify

Requirements: Linux containers, Docker Engine/Compose, a POSIX shell, curl, and the
verified executable JAR. On Windows, Git Bash plus Docker Desktop is supported;
use an absolute `C:/...` JAR path. Secrets are generated with the pinned JRE
image's OpenSSL, not stored in the repository or passed in command arguments.

```sh
export FLOWPAY_JAR="$(pwd)/target/backend-0.0.1-SNAPSHOT.jar"
sh ops/monitoring/tests/smoke.sh
```

The script creates a fresh `flowpay-monitoring-<timestamp>-<pid>` project and
external temporary secrets directory. It rejects an existing project, validates
the configuration/provisioning contract, runs promtool/config/alert fixtures,
starts owned PostgreSQL/RabbitMQ and the prod-profile JAR, and checks the actual
Grafana provisioned dashboard, every PromQL target, metric ingestion, rules and
stored HTTP/provider/Outbox/Ledger/Webhook-materialization trace. It then stops
**all four** monitoring services, repeats Payment/Refund/replay/conflict checks,
restarts monitoring, and verifies fresh telemetry and six balanced Ledger posts
for the three payments/refunds. It does not retry non-idempotent fixture writes.

The EXIT trap removes **only this invocation's disposable containers/network/
volumes**. Those fixture database/trace/metric records are irrecoverably removed.
The printed external synthetic secrets directory remains for manual deletion;
protect it with host ACLs (0700 on Linux). Never use these keys outside this lab.
The script does not touch dev resources, `.env` or user-owned `.github/`.

To keep a lab for inspection, use a new `flowpay-monitoring-...` project, set
`LAB_SECRETS_DIR` to a fresh protected directory outside the repo, initialize it
with `tests/init-secrets.sh` (requires OpenSSL), and run:

```sh
docker compose --env-file /dev/null -p flowpay-monitoring-review -f ops/monitoring/compose.yaml config --quiet
docker compose --env-file /dev/null -p flowpay-monitoring-review -f ops/monitoring/compose.yaml up -d
docker compose --env-file /dev/null -p flowpay-monitoring-review -f ops/monitoring/compose.yaml run --rm --no-deps probe live
```

Do not run the destructive smoke script against that retained project. Check its
exact project label and ownership before explicit cleanup. Scale the `app` service
without fixed container names if needed; update the scrape target list for every
replica, and retain `job="flowpay"`. Two-replica resource/failure acceptance is T15,
not claimed by this one-instance smoke.

## Access, data and limits

Grafana is bound only to `127.0.0.1:3300` (override `GRAFANA_LAB_PORT` on collision),
login `lab-admin` with the **external** `GRAFANA_ADMIN_PASSWORD` file. Anonymous
access/sign-up/plugin/update/reporting checks are disabled. All other ports,
including business HTTP, management 9091, DB, broker and collector, are unpublished.
All private services use a project-private internal Docker network without
external egress. Grafana also uses an isolated UI bridge to make its
loopback-published port reachable; that bridge is not a general egress firewall.
Do not attach
untrusted containers: its unauthenticated scrape/OTLP listeners trust that network.
This is not TLS/authenticated public ingress. T09's isolated DNS/TLS/network
fixture is separate from this telemetry smoke; T13 owns staging deployment.

Use dashboard `FlowPay / FlowPay — simulator operations` and Explore datasource
`FlowPay Tempo` for trace-ID lookup. IDs remain diagnostic identities, never meter
labels. API latency is a mean (no server histogram is currently configured);
provider p95 uses the existing fixed buckets, not precise raw percentiles. Empty
rate charts at idle/first scrape are not errors. Queries show normalized outcomes,
Idempotency replay/conflict, queue consumers/DLQ, overdue work, JVM/pool/CPU and
telemetry health. The collector has no debug/log payload exporter.

Prometheus scrapes/evaluates every 15s (5s scrape timeout), retains at most 48h or
256MB of TSDB blocks (whichever limit applies; WAL/head add space). Tempo retains
local blocks for 24h, with 256KiB per-trace limit; this is time retention, **not** a
hard disk quota. Host disk monitoring/storage sizing remains T13/T14. Collector
memory limiter is 160MiB + 32MiB spike; queue 256 batches, max batch 512 spans,
2s send timeout and 30s retry budget. Telemetry may be dropped. Container memory/
CPU budgets are finite lab guardrails, not measured production tuning.

Shared DB gauges use **max over fresh replicas**, never sum. Sample age <=120s
is considered fresh with this lab's default 30s sampler. Stale/NaN/missing data
are not zero; stale gauges are excluded, and the metrics alert reports degraded
visibility. Revisit this policy if changing the sampler cadence (valid up to 5m).
Counters sum rates across instances but are best-effort and restart/crash sensitive.
No metric proves financial amounts, exactly-once processing or completed delivery.

## Alert policy and resolution

Rules are evaluated in Prometheus and visible in the dashboard/Prometheus API via
the private network. There is **no external notification/paging route**, HA monitor
or vendor account in this lab. Prometheus cannot report its own total outage;
operator checks/an independent monitor are required. Adding a receiver/paging
policy needs separately approved operator credentials/destination. Never interpret
silent monitoring as a healthy service.

All thresholds below are provisional operational guardrails, not throughput/
latency SLOs. T14 reviewed workload-derived targets before independent acceptance.
The [T14 harness](load-testing.md) measures controlled750ms/first503
subscribers on the frozen T13 host. The owner delegated selection of its
calibrated numeric targets before independent acceptance/soak.
That acceptance passed, including30-minute soak; stress durable delivery lag
p99 is210.47s and drain180.86s, versus soak17.25s/16.32s. The shared cached
overdue-age gauge reached141.67s during soak; it is not filtered to new-scenario
rows. The benchmark does not collect an alert firing/notification timeline, so
do not infer that no alert fired or equate a cached age with durable lag.
Retain the existing120s age/85% heap/80% pool and error guards; do not raise
an operational alert threshold merely to hide the deliberate stress backlog.
Report resulting alerts and durable drain/recovery alongside the workload.
Every alert has no `keep_firing_for`: it resolves on the first successful 15s
evaluation where the predicate clears. Rate-window conditions can take up to 5m
to decay. Missing telemetry can also make an alert disappear: recovery requires
fresh scrapes and durable/business evidence, not disappearance alone.

| Alert | Predicate / hold | Severity | Recovery evidence |
|---|---|---|---|
| FlowPayTargetUnavailable | Required scrape `up=0` / 1m | critical | Successful fresh scrape and readiness checks |
| FlowPayMetricsStale | Sample age >120s, missing or NaN / 1m | warning | Both owners sampled successfully; age <=120s |
| FlowPayOutboxDelayed | Fresh oldest unpublished age >120s / 2m | warning | Outbox drains; fresh age <=120s |
| FlowPayWebhookDelayed | Fresh overdue age >120s / 2m | warning | Due work drains or consciously reviewed disabled endpoints |
| FlowPayWebhookDead | Fresh DEAD count >0 / 1m | warning | Reviewed deliveries leave DEAD through supported retry flow |
| FlowPayConsumerDlq | Either dedicated DLQ messages >0 / 1m | critical | Reviewed remediation/replay completed; DLQ empty |
| FlowPayConsumerUnavailable | Either required queue has <1 consumer or series absent / 2m | critical | Both queues present with active consumers |
| FlowPayHeapPressure | Used/max positive heap >85% / 5m | warning | Sustained lower heap and GC progress |
| FlowPayPoolPressure | Active/max DB pool >80% / 2m | warning | Pool lower and no accumulating waiters |
| FlowPayCpuPressure | Process CPU >90% / 5m | warning | Sustained lower CPU with completed work |
| FlowPayApiErrors | 5xx fraction >10% over 5m and >=20 requests / 5m | warning | Fresh low error rate and successful logical operations |
| FlowPayCollectorExportFailure | Failed span-send rate over 5m >0 / 2m | warning | Backend available, failures decay, new trace retrievable |

### Target unavailable

Check the exact project's container states, listener/network and scrape target.
Use `/livez` and `/readyz` inside the lab. Broker/telemetry outage is not DB
readiness failure; do not remove durable business acceptance solely for collector
outage. Restore the failed target/configuration; verify a new scrape/operation.

### Metrics stale

Check sampling is enabled, its cadence, pool saturation and successful sampling
counter for both OUTBOX/WEBHOOK. Query timeout is 2s but pool acquisition can take
longer. Do not enable scrape-time DB reads or sum cached replicas. Missing gauge
registration may require a reviewed restart; retain financial records unchanged.

### Outbox delayed

Check broker reachability, relay enabled, confirm/return outcomes and next retry.
Keep exponential backoff/jitter. Confirm PENDING/FAILED work drains and Ledger/
Webhook consumption follows; PUBLISHED alone is not proof of consumer completion.
Never manually mark records published or delete source events to clear an alert.

### Webhook delayed

Check worker enabled, leases, due times, disabled endpoints, HTTP outcome groups
and pool pressure. Overdue disabled endpoints are intentionally counted. Fix the
root cause within existing URL/security controls; do not shorten production
retry/jitter/lease budgets or bypass fencing. Verify retained attempt history.

### Webhook dead

Review endpoint ownership/configuration and safe failure classification. Use
the existing authenticated asynchronous DEAD-retry API after remediation, not
direct SQL mutation. Retry may deliver a duplicate externally. Disabled endpoints
cannot simply be re-enabled in this MVP; communicate the supported limitation.

### Consumer DLQ

Inspect only bounded event metadata on the owned queue; never dump signed bodies,
credentials or raw exception content. Distinguish malformed/contradictory events
from transient infrastructure failure. Preserve DLQ/history and Ledger invariants.
Do not automatically purge/replay. A reviewed repair/replay requires operator
authorization, then verify duplicate-safe Ledger/materialization outcomes.

### Consumer unavailable

Check topology declaration, queue names, enabled flags, listener connection and
broker status. Zero DLQ consumers is normal; only the two event queues are tested.
Recover listener/configuration and verify both consumer gauges plus actual work.

### Resource pressure

Compare JVM heap/GC, process CPU, pool active/max/pending and workload. Check slow
DB/network operations without logging secrets or making transaction boundaries
span HTTP. Do not blindly increase replicas/pool size (shared DB load multiplies).
Capture sanitized durations/resources for T14 calibration before changing budgets.

### API errors

Inspect bounded error codes/request correlation and compare source state with
Idempotency replay. Never automatically retry a financial mutation with a new
key. UNKNOWN is ambiguous, not a terminal decline. The 20-request floor suppresses
low-traffic noise; this alert does not replace per-request error handling.

### Collector export failure

Check Tempo readiness/retention/disk and Collector queue/memory limits. Restore
monitoring without altering financial state. Export retries may drop old spans;
recovery means a **new** controlled trace is visible, not all outage spans restored.

## P8-T09 affected-topology rerun (2026-10-11)

Project `flowpay-monitoring-1791716846-865` exits 0 using the final T09 JAR and
unchanged pinned Temurin 21.0.10+7 JRE. SHA-256:
`3bab47c2f277023151cf0db951391fb4a0dcc3e6353c32bc116c8ca25db76e67`.
Native config/rule/Collector checks, prod startup with fixed proxy settings,
Grafana provisioning/dashboard targets, live metrics and stored traces pass.
All four monitoring services stop without breaking financial/replay/conflict
checks; recovery ingests a fresh trace and metrics. Final SQL verifies three
partially refunded payments, three successful refunds, six unique Ledger
transactions, twelve entries and zero imbalance. This does not exercise actual
merchant TLS; the separate T09 dual-stack fixture does that.

Invocation-owned containers/networks/data volumes are removed and ownership-label
checks find zero residual resources. The external synthetic secrets directory
is retained for manual deletion, as documented by the script; no dev/shared
resource, `.env`, `.github/` or production credential was touched. Dependency/
image scanning, real staging ingress/firewall and capacity remain T11-T15 gates.

## Verification sources

P8-T10 affected-client rerun (2026-10-13): `smoke.py` supplies the required
`X-FlowPay-CSRF: 1` explicitly for auth POSTs; the guard is not disabled in the
fixture. Project `flowpay-monitoring-1791865252-1494` passes native provisioning,
metrics/stored traces, all-monitoring outage/recovery and the unchanged final
three-payment/three-refund/six-posting/twelve-entry balanced Ledger audit.
T10 final JAR SHA-256:
`240a95bc61895d095d3f0becff65daa078a2a367cf9ff806efdeafa2a3878401`.
The monitoring definition remains internal-only; it is not the new NGINX ingress
gate or TLS staging. All owned resources are removed; external synthetic secret
files remain for manual cleanup as recorded in `api-security-review.md`.

Pinned images/configuration follow the official [Prometheus release](https://github.com/prometheus/prometheus/releases/tag/v3.15.0),
[Grafana release](https://grafana.com/grafana/download/13.2.3?edition=oss),
[Collector release/configuration](https://github.com/open-telemetry/opentelemetry-collector-releases/releases/tag/v0.162.0),
and [Tempo 2.10.4 configuration source](https://github.com/grafana/tempo/tree/v2.10.4/example/docker-compose/local).
Tempo 2.10 is a maintained branch under its [published version policy](https://grafana.com/docs/tempo/latest/set-up-for-tracing/setup-tempo/recommended-versions/).
Compatibility is verified by actual image startup, not inferred from tags.
Use [promtool rule fixtures](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)
for deterministic firing/resolution; [RabbitMQ detailed metrics](https://www.rabbitmq.com/docs/prometheus#prometheus-endpoints-metricsdetailed)
are restricted to two required queues/two DLQs. Exact digests are in Compose.
These checks are not a vulnerability scan/SBOM or upgrade approval; T11/T12 own
those gates before deployment. No floating `latest` or paid service is used.
