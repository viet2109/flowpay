# Load, stress and soak verification (P8-T14)

## P8-T18 candidate reacceptance

The original T14 manifest, proposal and owner approval are preserved verbatim
(apart from checkout line endings) in `ops/load/history/t14/`. The historical
measurements below apply only to that original artifact; they are not acceptance
of the security-remediated candidate. Its reports were also preserved in an
external local evidence backup before starting the new measurement chain.

The active manifest now binds the remediated JAR/image and reviewed Dockerfile.
The Docker VM currently reports 3,920,220,160 bytes RAM. A strict preflight
rejected its 4,096-byte change after bootstrap cleanup; the active exact-host
declaration was reviewed before a new calibration, not bypassed. Neither host
differences nor artifact changes are
silently accepted. All rates, routes, timeouts, retry/jitter, resource limits,
numeric targets and the independent 30-minute soak are unchanged. Fresh
calibration passed; the old approval was not used to start acceptance. Following
the owner's delegated unchanged-target selection and request to continue, a new
post-calibration binding was recorded before the new empty acceptance project.
Do not reuse or edit historical result hashes.

Earlier source and image scans passed with the separately documented bounded
false-positive review; later release-time expiry metadata requires a fresh
source scan. That does not establish performance, recovery, a clean
committed release, external deployment, or a production SLO. Historical blocked
security statements below describe T14, not the current security gate.

### Fresh calibration and unchanged-target decision

The remediated calibration ran 2026-10-17T18:48:45.039501Z through
18:57:32.488864Z. Report SHA-256:
`5e18faccf2c5362cd6bd8a357a221dddcb9119b5d07de7ad5a958f2238a111b7`;
workload SHA-256:
`5d25e9752a27b59a8dbf274c83d9d989a66a71481c96b422959c957151eb6ff8`.

| Probe | Completed/started workflows | List queries | Policy429 | API aggregate p99(ms) | Outbox p99(s) | Webhook p99(s) | Drain(s) |
|---|---:|---:|---:|---:|---:|---:|---:|
| sanity | 2/2 | 4 | 0 | 160.47 | 0.97 | 20.06 | 16.20 |
| calibration | 7/7 | 121 | 0 | 61.57 | 0.96 | 17.23 | 18.05 |
| peak_probe | 16/16 | 226 | 0 | 55.55 | 10.79 | 35.15 | 29.13 |
| stress_probe | 11/61 | 600 | 50 | 78.32 | 29.09 | 123.10 | 109.69 |

All four probes have zero unexpected/contract errors and dropped iterations;
financial/HMAC/identity/retry audits and owned cleanup pass. Sampled heap maximum
is12.29%, pool pending0. These exploratory results are not acceptance or a new
capacity/SLO claim. At18:57:47Z, after this completed calibration and before
acceptance, the agent recorded the unchanged targets under the owner's existing
delegation and continuation request. At that calibration checkpoint the
independent acceptance was pending; no final load pass was inferred from probes.

Rejected attempts are retained separately: bootstrap failed before workload;
another diagnostic repetition identified RabbitMQ exit1 with OOMKilled=false,
and an exact-host preflight rejected a4,096-byte MemTotal change. The final
calibration's observational wrapper made no workload/timeout/resource change;
it captured no broker failure because bootstrap succeeded. Neither the later
pass nor a read-only projection racing final cleanup establishes the cause of
the earlier broker exit. Retain this startup-stability limitation.

### Interrupted acceptance and independent restart (2026-10-18)

The first remediated acceptance reached soak but was interrupted without its
final report. On continuation, its benchmark process was absent and all eleven
owned containers were stopped: the completed one-shot topology exited0; ten
services exited255 with OOMKilled=false. Their clustered final timestamps do
not establish an application defect, OOM or the cause of interruption. No
completed soak, capacity result or acceptance is inferred from partial output.

Safe interruption report SHA-256:
`a30e33b5b22e1ccf606cf3986f0938710dfbd0999f33e2e2ee28c7b75c4ffc13`
(`target/ci-safe/load-acceptance-interrupted.json`). Exact UUID/image/labels and
stopped state were revalidated before removing its11 containers,7 networks,
5 volumes and one external synthetic-key/fixture directory. Absence was
verified. That disposable fake data/keys cannot be recovered; bounded evidence,
historical backups and the user's unread root `.env` remain untouched.

The host still matches the existing calibrated exact declaration, so no host,
approval timestamp or target was changed. A new canonical acceptance invocation
starts from a different empty project and repeats every profile, including the
full1,800-second soak; no time or samples are joined across attempts.212 gate
controls passed separately on Windows and pinned Linux before subsequent
native-script controls. At restart no final load pass was claimed;
failure/restore/native/clean-committed gates remain separate.

### Independent final-candidate acceptance — PASS (2026-10-18)

The new empty-project run completed2026-10-18T01:57:42.811487Z through
02:43:52.210688Z (about46m09s), including bootstrap, real retry/drain and owned
cleanup. No time/samples from the interrupted attempt are reused. The unchanged
thresholds and workload hash above still match the post-calibration approval.
Safe report SHA-256:
`1bcff0e7ea8ae5f461e2b4ccd421f438f4826ab375cf7c5538ad9248b09ff0bc`.

| Profile | Complete/started workflows | List queries | Requests | Policy429 | Drops | Aggregate p95/p99(ms) | Outbox p99(s) | Webhook p99(s) | Drain(s) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| warmup | 4/4 | 30 | 66 | 0 | 0 | 136.25/164.25 | 0.78 | 17.54 | 18.27 |
| steady | 13/13 | 241 | 358 | 0 | 0 | 79.00/144.15 | 1.01 | 17.38 | 17.60 |
| peak | 41/41 | 601 | 970 | 0 | 0 | 100.55/167.62 | 24.93 | 87.13 | 61.60 |
| stress | 24/120 | 1189 | 1956 | 96 | 13 | 70.00/331.10 | 40.77 | 253.59 | 221.06 |
| soak | 181/181 | 3601 | 5230 | 0 | 0 | 27.00/47.00 | 2.58 | 17.18 | 18.13 |

All profiles have zero unexpected/contract errors and pass financial/HMAC/
identity/attempt audits. Every regular measured route meets its500ms p95/
1000ms p99 budget: worst regular p95 is177.60ms (steady refund), worst p99
263.80ms (peak confirm), not the query-dominated aggregate. Provider p99 upper
bucket bounds remain10ms, not exact real-provider latency.

Stress is explicitly **not normal-load capacity acceptance**:13 offered
iterations were dropped and96 workflows stopped at the unchanged edge policy.
Only24 of120 started financial workflows completed. All started/limited/dropped
work reconciles, including duration-boundary arrivals; partial persisted work
is audited, not erased or counted as a complete workflow. Existing approval
already keeps stress drops/limits separate; no post-failure waiver, VU/resource/
rate-limit increase, lowered offered rate, retry change or hidden call retry is
used. The generator did not sustain every stress iteration, and backend maximum
capacity is still not established. Do not advertise2 financial workflows/s.

Soak generation lasted1801.90s with121 fifteen-second telemetry samples.
Heap first-/last-third means74,119,650.4/74,388,509.6 bytes grew268,859.2 bytes
(0.256MiB), below128MiB; this is not proof of no long-term leak. Sampled heap
max12.45% across acceptance and pool pending0. The new soak's shared Webhook
overdue gauge max is3.00s, but generation-window summaries do not establish
that no alert fired during the complete stress/drain window; no notification
timeline was collected. Operational alert thresholds are unchanged.

Final audit:360 payments,349 successful confirmations,282 refunds,631 unique
balanced postings,1,262 immutable events and3,786 delivered subscriptions.
All owned project resources and temporary synthetic keys were removed and
absence checked. Only bounded evidence remains; fake lab data is not recoverable.
Full Java verification is not rerun for unchanged application/build/test inputs:
the remediated1,407-test/196-suite artifact remains exact.215 offline controls
pass on Windows and pinned Linux, including18 fake-Docker preflight negatives.
New two-instance failure, restore, runbook, dedicated native and full source
repetitions also pass; their dated reports are separate. Clean43ae0f3 source
attestation/release preflight and hosted run39110490452 subsequently PASS.
Phase8 is DONE/FROZEN in the selected lab scope; see the
[exit audit](production-exit-audit.md). No local performance approval transfers
to an independently rebuilt hosted artifact, public deployment or different host.

## Scope and approval boundary

This is a synthetic-only operational harness, not an external deployment,
production SLO, real-money workload or clearance of T11/T12 security findings.
The [T13 artifact/staging boundary](staging-deployment.md) remains in force.
No application Java, dependency, API, migration, index, locking, pool, resource,
ingress budget, sampler or retry setting is tuned for this task.

T14 has two distinct stages:

1. Exploratory calibration, including low-rate protocol sanity and bounded
   probes. A successful calibration is **not acceptance**.
2. A new acceptance environment/run after the owner approves numeric targets
   against that calibration and the exact workload/artifact. Targets must not
   move afterward to make a failing run pass.

`ops/load/acceptance.json` records the owner's delegated selection of the
unchanged calibrated proposal on2026-10-17. The `accept`
command refuses to create a stack until a dated owner approval follows a valid
calibration and matches its report hash, executable workload hash and image.
There is no auto-approve or security-skip flag. Changing a workload file,
manifest, host or the referenced T13 configuration invalidates comparison and
requires new calibration/review. The agent records approval only after an
explicit owner decision; the JSON file is not a substitute for that decision.

## Frozen workload and environment

`ops/load/manifest.json` freezes the tested T13 JAR/image, Linux/x86_64 Docker VM
with4CPU/3,920,216,064 bytes RAM, kernel/Docker/Compose versions, one app replica,
LF-normalized T13 configuration hashes and the offered-load schedules. Other
hardware requires an intentionally reviewed manifest, not an automatic fallback.

Load generation uses official **Grafana k6 2.0.0**, manifest digest
`sha256:a33a0cfdc4d2483d6b7a3a22e726a499ff2831a671a49239104cd34a9937523c`,
resolved from the official registry on2026-10-17. It is an isolated non-root,
read-only, capability-free container with256MiB/1CPU, not a Maven/runtime
dependency. No extensions, remote JS imports, cloud service, usage reporting,
HTTP debug logs, external merchant or paid account are used. A separate pinned
Python observer reads private telemetry; its costs also share this VM.

The generator runs **on the same constrained VM** as the app, PostgreSQL,
RabbitMQ, enforcing proxy and full monitoring stack. Finite cgroup caps are not
reserved memory; summed caps plus test containers exceed physical RAM. Record
any OOM as failure, not usable capacity. Co-resident generator/telemetry, full
T13 trace sampling, small seeded data and one ingress peer all affect results.
This is not a cross-machine, multi-instance, day-long or universal capacity claim.

The manifest is written before calibration, independently of numeric acceptance
targets. Every scenario is open-model constant-arrival-rate, not a closed loop
that silently lowers offered traffic when requests become slow. Fixed VU bounds
and `dropped_iterations` are retained; a generator unable to offer the intended
load is not evidence of backend capacity. No transparent retry of a failed
financial call is used to make request success rates look better.
Every offered iteration must reconcile to started/completed/policy-limited or
dropped work. Unexplained aborted workflows/queries or missing latency samples
fail the run instead of being excluded from throughput.
Native k6 2.0 can dispatch a duration-endpoint arrival for each executor. The
report keeps those calls and shows both nominal and actual offered totals,
with at most one endpoint arrival per each of the two scenarios. This is not
an allowance for missing/aborted started work: exact started/completed/limited
and k6 iteration counters must still reconcile, and drops remain explicit.

Each complete financial workflow has9 HTTP requests through verified HTTPS:

- Create100,000VND; identical-key replay; changed-amount conflict.
- Confirm; exact replay of its snapshot.
- Refund1,000VND; exact replay of its snapshot.
- Read the payment and its paged list; validate the public response envelope.

A separate query scenario lists page0/size20. Keys are unique per run/profile/VU/
iteration. One seed workflow is excluded from workload summaries but included
in all whole-project financial audits. The same synthetic merchant/API key is
used across profiles; setup avoids repeatedly exercising auth rate limits or
depending on a15-minute dashboard JWT during the soak.

| Profile | Duration | Workflows/s | Additional list queries/s | Purpose |
|---|---:|---:|---:|---|
| sanity | 15s | 1/15 | 1/5 | Low-rate contract and metrics check |
| calibration | 60s | 1/10 | 2 | Exploratory steady sample |
| peak_probe | 45s | 1/3 | 5 | Exploratory higher load |
| stress_probe | 30s | 2 | 20 | Bounded ingress/read-pressure probe |
| warmup | 30s | 1/10 | 1 | Independent acceptance warmup |
| steady | 120s | 1/10 | 2 | Declared regular workload |
| peak | 120s | 1/3 | 5 | Declared peak |
| stress | 60s | 2 | 20 | Expected policy pressure, not hidden successes |
| soak | 1800s | 1/10 | 2 | 30-minute bounded trend observation |

One completed workflow has7 mutations. Stress can exceed the existing single
TCP-peer10mutations/s edge budget. Expected `429 RATE_LIMIT_EXCEEDED` is counted
separately; partial workflows remain in the database and audits, not deleted.
Read traffic also exercises the app without raising the financial rate limit.
An unobserved backend saturation point must be reported as **not established**,
not the configured offered rate. No ingress-policy bypass is authorized here.

Three subscribers receive each public event: fast204, slow204 after750ms, and
first-attempt503 followed by204. The real five-delay schedule, additive jitter,
HTTP timeout, lease and normal batch/worker behavior remain unchanged. Failed
deliveries must actually wait for production retry scheduling; no backdating,
direct state mutation, manual retry or shortened schedule is used. This measures
transient receiver recovery, **not** the full hour-long path to DEAD under a
permanently failing receiver. It is also not a T15 infrastructure-failure drill.

The baseline intentionally uses SUCCESS provider simulators. Expected409
Idempotency conflicts, policy429, unexpected transport/5xx/contract failures,
expected202 and business-decline counts are distinct. The last two are zero in
this workload: an unexpected202/decline is not silently accepted as success.
T13 supplies UNKNOWN/DECLINED contract evidence, not their throughput here.

## Measurements and invariant gates

Client-observed API trends include the synchronous HTTPS call's full wall time,
including connection/TLS acquisition, with count/min/median/mean/max/p95/p99 and
fixed operation names. No public resource URL/ID, key, email, token, payload or
signature becomes a metric tag. Tiny samples still show their count; an apparent
p99 from a handful of calls is descriptive, not a statistically reliable tail SLO.
The same approved API budgets apply to each measured route, not just a
query-dominated aggregate. Provider upper-bucket p99 has its own approved bound.
Warmup is recorded and audited but does not assert steady-state latency; stress
keeps all observed policy limits/drops and is not labelled a normal-load pass.

Provider p95/p99 use pre/post **fresh scrape counter deltas** from the existing
fixed buckets. The report records an upper bucket bound, not fabricated exact
latency or an average of per-instance percentiles. Empty/overflow tail samples
are null/insufficient, never0. This task does not enable new server histograms
or confuse the existing HTTP mean metric with an HTTP p95.

Read-only PostgreSQL projections measure publication lag and durable delivery
lag p95/p99 for new scenario rows. Receiver observations separately measure
first-success end-to-end lag, handler duration,503 count and exact-body/HMAC
identity. Recovery/drain is measured from generation end until **both consumer
effects** materialize and all attempts finish, not merely until Outbox=PUBLISHED.
The hard600s bound prevents an uncontrolled lab run; it is not the future
approved latency/drain threshold and is never extended to fabricate acceptance.

Prometheus ranges retain sample counts/min/max/mean and first/last-third means
for CPU, heap, pool active/pending/max, GC pause rate, cache age and backlog/age.
Missing/NaN telemetry or stale shared-DB gauges are errors, not substituted zeros.
Readiness/running/OOM checks accompany every scenario. Heap slope is an
observation over this duration, not proof of absence/presence of a memory leak.

After each scenario the SQL audit checks:

- Refunded+reserved never exceeds payment; totals agree with actual refund rows.
- Replays do not create extra provider attempts/refunds; partial429 workflows
  have consistent state instead of being forced terminal.
- Exactly one balanced two-entry Ledger posting per successful payment/refund,
  correct amounts/references and no orphan or duplicate posting.
- Source facts, public events and3-way deliveries match; stable public payload
  identity and immutable signed bytes survive retries.
- Attempt history finishes, flaky retries wait at least10s, no lingering
  Idempotency PROCESSING, no extra/DEAD/dangling delivery.

Database probes force PostgreSQL `default_transaction_read_only=on` and a10s
statement timeout. Audits may inspect the entire owned database; they do not
join business orchestration or expose internal IDs in public API/report output.

## Commands, safe evidence and status

Requirements: the unchanged verified T13 JAR/image and successful matching
fresh/Phase7 staging report in `target/ci-safe`, frozen host and pinned tools.
Do not run another fixture using11.203.0.0/24 concurrently.
The runner refuses a host with other active containers at entry; it never stops
or adopts them. Host processes/VM scheduling still remain a measurement limit.

```sh
python3 -B -m unittest discover -s ops/ci/tests -v
python3 -B ops/load/benchmark.py calibrate
# Review calibration; owner explicitly approves the fixed numeric proposal.
# Only then record that approval and exact report/workload hashes.
python3 -B ops/load/benchmark.py accept
```

Calibration uses a new UUID-owned project, explicit empty env-file, fresh
temporary synthetic keys and an isolated internal merchant bridge. Acceptance
uses another new project, never the calibration data, a dev database or a live
merchant. The runner restores its environment and cleans only resources proven
absent before this invocation. Root `.env` and local plans/index are not read,
mounted, staged or uploaded. No global prune or retained-state reset is used.

Reports are `target/ci-safe/load-calibration.json` and `load-acceptance.json`.
Only fixed names, numeric summaries, hashes and approved threshold values are
retained. No raw k6 HTTP logs, SQL rows, payloads or credentials are exported.
Source/image scan gates remain independently blocked. Do not upload the raw
temporary fixture or run a30-minute benchmark inside the normal50-minute
verification job. Its pinned generator contract controls run separately; Python
regressions join the existing CI discovery. Hosted-runner hardware differs and
cannot silently reuse this host's approval.

Current stage: **P8-T14 DONE for the declared synthetic workload**; independent
warmup/steady/peak/stress/30-minute soak acceptance passed on2026-10-17.
The owner delegated target selection; the unchanged proposal is now fixed in
`acceptance.json` before the independent run. A bootstrap failure ran no workload;
subsequent preflight detected a4096-byte Docker MemTotal difference and rejected
reuse. The manifest now records the actual host; a fresh successful calibration
binds the unchanged numeric targets before acceptance. No thresholds are relaxed.
No application inputs changed, so the T13 verified1,407-test artifact is reused
rather than claiming an unchanged full Maven suite was rerun. Focused/native
controls and the independent acceptance are recorded separately below.
No push/merge or T15 work is included; release security remains BLOCKED.

## Calibration checkpoint (2026-10-17)

The final immutable workload hash is
`2c182ebc8b15069f38f5e728fe493b2343bfc660878c05825f2fd1ea5f9f42b3`.
Full safe calibration report SHA-256 is
`59573d12ef57c9caa33a186b4e38fccdf3e71c4c3b105b27350b6938ecdfbda7`,
completed2026-10-17T08:09:01.416807Z. It records the frozen T13 image/JAR/host
from the manifest, not an inferred release acceptance. Do not reformat/replace
this report after approval; a clean/rebuild that removes it requires calibration
and review again, not reconstruction from this rounded table.

| Probe | Completed workflows | List-query iterations | Policy429 | API aggregate p99(ms) | Outbox lag p99(s) | Webhook lag p99(s) | Drain(s) |
|---|---:|---:|---:|---:|---:|---:|---:|
| sanity15s | 2 | 4 | 0 | 201.46 | 2.91 | 20.18 | 16.10 |
| calibration60s | 7 | 120 | 0 | 81.44 | 2.71 | 17.40 | 18.82 |
| peak_probe45s | 15 | 226 | 0 | 55.40 | 11.08 | 32.14 | 24.74 |
| stress_probe30s | 9 of61 started | 601 | 52 | 40.09 | 27.23 | 119.82 | 106.48 |

All profiles have0 unexpected server/transport/contract errors and0 dropped
iterations. Actual offered iterations are6/127/241/662 versus nominal4/126/240/
660, including the recorded k6 duration-boundary arrivals, not discarded calls.
The largest observed per-route API p99 was215.13ms during low-rate cold sanity;
peak-probe refund p99 was59.76ms. Per-route counts/tails are retained in the
full report. The lower stress aggregate is **not improved capacity**: its mix
contains many quick queries/policy rejections and only9 complete workflows.

Provider p95/p99 upper bucket bounds are10ms, not exact simulator latencies.
All sampled pool-pending values are0; sampled heap ratio max12.52%, process CPU
ratio max26.62%. Gauges are sampled15s, so these are not continuous maxima or
proof that short spikes/long-run leaks cannot occur. No backend saturation point
was established; observed bottlenecks are the per-peer edge mutation budget and
the deliberately slow/transient-failure Webhook pipeline. No production resource,
pool, rate, index or retry tuning is justified/implemented from this short run.

Final audit:86 payments,85 successful provider transactions,36 refunds,121 unique balanced
postings,242 immutable public events and726 delivered subscriptions, including
the seed and partial stress workflows. Every scenario's whole-project invariant
and HMAC/identity/attempt audit passes; all owned containers/networks/volumes and
temporary synthetic keys were cleaned, with no OOM. Acceptance preflight was
negatively tested: owner-pending approval fails before any stack creation.

114 focused Python controls pass on Windows and pinned Linux Python; native
k6 protocol/classification/summary-schema and workload inspection pass.
`git diff --check` passes. The existing1,407-test Java/JAR evidence is reused,
not counted as a new run. Initial fixture iterations corrected Compose's string
memory normalization, summary diagnostics and inclusive duration-boundary
accounting. An in-flight exploratory run was rejected when the harness changed;
it was not promoted. Final calibration uses the single unchanged hash above.

### Fixed numeric acceptance (owner-delegated selection)

`ops/load/proposal.json` preserves the original proposal bound to the report/
workload hashes. The owner asked the agent to select the recommended direction;
`acceptance.json` records that delegation and the unchanged values before the run.
The proposal
allows headroom for an independent longer run, including a60s rather than30s
stress and a30-minute soak. It is not derived from a universal payment SLO:

- Regular steady/peak/soak API p95<=500ms, p99<=1000ms **for each route**;
  provider p99 upper bucket bound<=50ms.
- Outbox lag p99<=60s; Webhook lag p99 and post-scenario drain<=300s.
- Zero unexpected server/transport/contract/invariant failures; zero drops and
  policy429 outside stress. Keep stress drops/limits explicit, not successes.
- Sampled heap<=85%, pool pending<=2; first/last-third soak heap-mean growth
  <=128MiB. Resource sampling/trend limits are not a leak/HA proof.

Existing120s operational backlog alerts and85% heap/80% pool guards are retained;
the controlled stress backlog is not a reason to raise them. Security findings
remain blocking. The independent run below passed without moving these targets.
Push is not authorized by this task. No T15 work is started.

## Independent acceptance (2026-10-17)

Status **accepted**; a new empty UUID-owned project, not the calibration data.
The complete run lasted08:09:39.447126Z-08:53:31.549264Z (about44 minutes),
including bootstrap, real retry/drain, audits and cleanup. The approved numeric
values exactly match the original proposal; no post-failure relaxation, runtime
tuning, state reset or policy bypass occurred. Workload and calibration hashes
still match the approval. Acceptance report SHA-256:
`62b6b5adccb886f861276fd6f3fa36af35222e971c52845e64c55e98ae6b8bf9`.

| Profile | Completed/started workflows | List-query iterations | Requests | Observed req/s | API aggregate p95/p99(ms) | Outbox p99(s) | Webhook p99(s) | Drain(s) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| warmup30s | 4/4 | 30 | 66 | 2.09 | 67.50/82.75 | 0.99 | 17.47 | 18.39 |
| steady120s | 13/13 | 241 | 358 | 2.95 | 39.30/82.87 | 1.82 | 16.93 | 15.84 |
| peak120s | 40/40 | 601 | 961 | 7.91 | 32.00/42.00 | 19.48 | 53.68 | 39.75 |
| stress60s | 8/120 | 1201 | 1945 | 31.63 | 30.00/47.56 | 29.00 | 210.47 | 180.86 |
| soak1800s | 181/181 | 3600 | 5229 | 2.90 | 21.00/52.72 | 2.58 | 17.25 | 16.32 |

Observed req/s is total measured requests divided by observed generator-process
seconds (including small setup/teardown overhead), not completed financial
workflows/s or a sustainable capacity guarantee. In-process k6 counter rates are
also retained in the report. The seed is excluded here and included in audits.
There are8,559 measured requests. Actual offered iterations34/254/641/1321/3781
versus nominal33/252/640/1320/3780 retain every duration-endpoint arrival.

All five profiles have zero server/transport/contract errors and zero dropped
iterations, with all started work reconciled. Only stress has policy limiting:
112 expected429, with8 workflows fully completed and112 stopped at a rejection.
Partial persisted successes are audited, not erased or counted as completed
workflows. The small stress p99 is not better financial throughput: its request
mix contains many fast list queries, conflicts and policy rejections.

Each regular route independently meets the fixed500ms p95/1000ms p99 budget.
The largest regular-route p95 is77.20ms (steady create); largest p99 is124.80ms
(soak refund), versus the aggregate52.72ms soak p99. Both provider simulators'
p95/p99 upper bucket bounds are10ms throughout, below50ms; these remain bucket
bounds, not exact provider latency. Every profile passes the fixed lag/drain,
resource, error and financial gates with an empty violations list.

The soak retains121 samples at15s spacing. Heap first-/last-third means are
72,751,153/73,458,148.6 bytes: growth706,995.6 bytes (0.674MiB), below128MiB.
Soak sampled heap max11.91%, process CPU ratio max11.05%, pool pending0 and
pool active max1 of10. Across acceptance, sampled heap max12.50%, process CPU
ratio max19.29%, pool pending0. The soak GC-pause-rate maximum is0.03614s/s;
first-/last-third means are0.00077/0.00378s/s. Retain that observation rather
than claiming zero GC cost or absence of a long-term leak. No service OOM or
restart was observed at checkpoints; final service-running/OOM audits pass.

Operational guardrails remain unchanged. Shared cached Webhook overdue age has
a141.67s sampled maximum during soak even though new-soak durable delivery lag
p99 is17.25s. These gauges are not filtered to scenario-created rows and may
include cached carry-over; the summary alone cannot locate the contributing
instant/rows. Generation-window gauges also do not describe the entire drain
window. Do not substitute them for per-scenario durable lag or claim that no
alert fired. Alert firing/notification timelines were not collected by this
benchmark; existing120s age/85% heap/80% pool guards are not raised to hide stress.

Final whole-project audit:359 payments (358 successful provider transactions,
one CREATED partial workflow),253 successful refunds,611 unique balanced Ledger
postings,1,222 immutable public events and3,666 delivered subscriptions. Every
scenario passes financial, deduplication, exact-body HMAC/identity and real retry
timing checks. Owned project containers, networks, volumes and temporary keys
were removed; only bounded numeric evidence is retained. Other resources and
the untracked root `.env` were not adopted or changed.

Final verification:114 focused Python controls pass again on Windows/Python3.14
and pinned Linux/Python3.13.12; native pinned k6 protocol/classification/summary
schema and workload inspection pass. No application Java/resources, dependency,
CI workflow or T13 runtime configuration changed, so no new full Maven run or
fresh security-scan acceptance is claimed. The unchanged verified T13 artifact
is used throughout. Security findings remain unwaived and release stays BLOCKED.
No backend saturation point, multi-instance/HA, day-long endurance, permanent
receiver-failure DEAD path, real provider latency or external deployment is
established. T15-T18 retain their separate operational/exit work.

Primary references: [k6 open vs closed models](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/),
[arrival-rate executor](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/),
[dropped iterations](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/),
[bounded custom summaries](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/),
[k6 2.0.0 release](https://github.com/grafana/k6/releases/tag/v2.0.0).
