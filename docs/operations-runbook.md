# FlowPay operational runbook (P8-T17)

## Scope and authority

This is the simulator Linux/container baseline, not a deployed public service.
The repository owner is the current incident commander and release approver;
there is no staffed on-call team, external paging destination or hosted frontend.
An operator needs explicit access to the selected staging environment. Docker
administrator access and its private networks are trusted, not a tenant boundary.
Never use development Compose, root `.env`, foreign volumes or fixture keys for
a retained deployment. Retained/public release remains **NO-GO**; this runbook
cannot authorize deployment or waive independent gates. P8-T18's reviewed
source/image security gates currently pass with the bounded
[source false-positive acceptance](secret-findings-review.md#approved-bounded-false-positive-acceptance),
not a real-secret waiver. Final operational repetitions now pass; committed-source
and hosted-CI checkpoints remain separate. See the [exit audit](production-exit-audit.md). The dated T17
rehearsal below is historical; the current candidate repetition is recorded next.

### P8-T18 final-candidate rehearsal — PASS (2026-10-18)

The current image/JAR repetition ran03:11:14.315445Z–03:12:40.728610Z.
All25 local link/source-contract checks and native read-only SQL/queue/health/
metrics/dashboard200/DELIVERED-retry409 controls pass. Exact19-table, sequence
and schema snapshots are unchanged; no changed table names. One payment/refund,
two unique balanced postings, four events, two deliveries, HMAC/source trace
and empty queues including DLQs pass. Owned resources/temporary keys were removed.
The preflight correctly records BLOCKED on the older source report lacking
required expiry metadata; a passing rehearsal is not release clearance.

Safe report SHA-256:
`8128d08f11d1cf20d03168a674455e6a57a1eacecf574bfd0614ae0cacb13eb5`;
executable workload SHA-256:
`a39609aee1945bf635f0c1cfbc03279ea36ce3dff369610f0e4170720306c2bd`.
215 offline controls pass on Windows and pinned Linux. No new DEAD exhaustion,
real DLQ replay, production mutation/key rotation or deployment is authorized.

| Class | Action | Required decision |
|---|---|---|
| R | Health, aggregate SQL/queue/metric diagnosis, existing authenticated delivery GET | Authorized read access; keep evidence private and bounded |
| A | Fence ingress, restore connectivity, restart one instance, revert compatible application configuration | Incident owner approves exact environment/resource, expected disruption and stop condition; infrastructure reversal does not undo remote effects |
| I | Manual HTTP retry, secret rotation/revocation, broker event replay, restore/cutover or data-volume removal | Fresh explicit action approval and impact review; remote effects/key invalidation/data loss may be irreversible |

Record incident time (Asia/Saigon), source/image/config/key **references**, affected
operation and stable error codes, last known-good checkpoint and the owner of
the next decision. Do not record tokens, raw Idempotency keys, credential hashes,
payloads, ciphertext, signatures or SQL/container/config dumps. Public case IDs
stay in the restricted incident record, never metric labels. If financial
consistency, key provenance or target ownership is uncertain: stop mutations,
preserve evidence and escalate to the repository owner. No generic admin API,
automatic reconciliation or repair/replay service exists.

## Read-only first response

Select the **existing approved** `flowpay-stage-` plus32-lowercase-hex project
from the deployment inventory; set `FLOWPAY_OPERATOR_PROJECT` to that exact name,
not a guessed/default/dev name. Run from the reviewed repository checkout:

```powershell
python -B ops/runbooks/runbooks.py check
python -B ops/runbooks/runbooks.py triage --project "$env:FLOWPAY_OPERATOR_PROJECT"
```

`check` verifies local document links/anchors, fixed SQL, current retry contract
and empty security exceptions; it is not release approval. `triage` revalidates
container project/service labels immediately before each exec. It makes only
bounded read calls: a fixed aggregate SELECT with default read-only transactions,
2s statement/1s lock timeout; Rabbit queue counters without message retrieval;
app `/livez`, `/readyz` and private9091 metrics with5s curl bounds. It uses existing
container credentials, not secret arguments. It never loads `.env`, writes
business data, fetches/purges messages, changes services or prints raw responses.
Unexpected projection fields fail closed. Unavailable/stale/nonfinite evidence
is not zero. Exit0 means those diagnostic sources were captured, **not** that
financial outcomes are correct; exit2 means at least one source is unavailable.
Invalid ownership/configuration is exit1. Do not keep retrying a failed command
against a different guessed project. The SQL is also reviewable in
[triage.sql](../ops/runbooks/triage.sql); do not append ad-hoc financial UPDATEs.

Keep metrics per app instance; cached shared-DB gauges use max over fresh
replicas, not sum. The triage projection removes arbitrary labels; use the
provisioned private dashboard for attribution, not aggregate guesses. A5s
diagnostic timeout does not change the application's30s DB pool timeout. A
DB outage can produce application readiness503 and edge503/504 while `/livez`
remains200; readiness alone says nothing about broker/consumer completion.

## Management and monitoring access

Use the approved private operator path to `observe`, never publish9091, broker
UI, PostgreSQL or telemetry to the Internet. T13 publishes only loopback TLS
ingress; unlike the older T08 lab, it does **not** expose Grafana on host3300.
Inside the trusted network, Grafana is `grafana:3000`, Prometheus `prometheus:9090`,
Tempo `tempo:3200` and app management9091. Obtain Grafana credentials from the
external secret store, not Compose output. Access provisioning is an owner gate,
not permission to attach arbitrary containers or disable TLS/authentication.

Use dashboard `FlowPay / FlowPay — simulator operations` and `FlowPay Tempo`.
[Alert predicates and holds](monitoring-runbook.md#alert-policy-and-resolution)
are authoritative in [rules.yaml](../ops/monitoring/prometheus/rules.yaml):
target down1m; stale metrics >120s for1m; Outbox/Webhook age >120s for2m;
DEAD>0 for1m; DLQ>0 for1m; missing/zero event consumers2m; heap>85% for5m;
pool>80% for2m; CPU>90% for5m; API5xx>10% with20 requests/5m for5m;
collector export failures2m. Alerts are provisional guards, not measured SLOs.
They resolve on a successful evaluation with a cleared predicate; disappearance
on missing telemetry is not recovery. There is no external notification route.
Prometheus cannot alert through its own complete outage; independent monitoring
and a staffed response plan remain release prerequisites.

## Database or broker outage

R: inspect readiness, service/OOM state, pool pending, queue consumers and the
bounded durable counts. Distinguish network/authentication/capacity from a
business decline. Do not log connection strings/credentials or clear tables.

A: after the owner approves the exact resource, restore the known-good network,
credential reference or service configuration. Restart only that resource if
required. Never perform global prune or delete a volume to fix readiness. Keep
ingress fenced when DB availability or business acceptance cannot be established.

Verify fresh app readiness, a reviewed same-key logical replay/query, source
facts/reservations, consumers and durable drain. A broker outage can leave
financial source commits successful while Outbox retries publication; do not
retry financial calls with a new key or mark those commits failed. T15 measured
broker drain21.20s and DB app503/edge504/liveness200, but offered **no financial
write during the DB outage**. Do not claim a tested acceptance/rejection contract
for such writes. See [failure drills](operational-drills.md#scope-and-fixed-expectations).

## UNKNOWN financial outcome

`PaymentTransaction.UNKNOWN` with payment PROCESSING, or an ambiguous refund
with refund PROCESSING and its reservation retained, requires investigation.
Check the existing authenticated payment/refund query and original logical
request privately; escalate to the owner with safe correlation and timing.
Do not infer success/failure from an HTTP timeout, missing metric, absent Ledger
posting, `PUBLISHED`, expired lease or elapsed wall time. There is no implemented
provider inquiry/reconciliation/admin endpoint to call. Do not re-charge,
release refund capacity, force FAILED/SUCCEEDED, reset Idempotency or invent a
new key. Preserve the unresolved financial state until a separately authorized
design/process can establish the provider outcome. This runbook supplies none.

## Outbox backlog and consumer DLQ

R: inspect unpublished counts, oldest-age freshness, relay enablement, confirms/
returns, broker availability, required event consumers and DLQ counts. The queues
are `flowpay.ledger.events`, `flowpay.webhook.events` and their `.dlq` queues.
DLQ consumers normally remain zero. Source `PUBLISHED` proves broker acceptance,
not successful Ledger/Webhook processing. Queue depth alone does not identify
every missing event; reconcile original facts and consumer records privately.

A: restore listener/broker/DB connectivity or a reviewed compatible consumer
configuration. Preserve capped exponential backoff **with jitter**, bounded
consumer retries, AUTO ACK-after-commit and uniqueness protections.

I: malformed/contradictory events and nonempty DLQs require owner review. Never
fetch with destructive auto-ack, purge, shovel/requeue wholesale, generate new
event IDs or reset PUBLISHED to PENDING. A real DLQ replay tool/procedure is
**not implemented**; leave messages retained and escalate. T16 approved only its
isolated empty-broker checkpoint case, not standing authority for retained or
production queues. Any separate approved recovery must establish source facts,
original IDs/routing/headers, consumer compatibility and expected duplicates,
then verify balanced unique Ledger and immutable Webhook identities/history.
Follow [checkpoint recovery](backup-restore.md#reconciliation-and-recovery-procedure),
not an improvised SQL repair. An alert is not authorization to replay.

## Webhook backlog DEAD and manual retry

R: inspect worker state, due/lease counts, disabled endpoints, HTTP outcome groups
and pool pressure. Dashboard JWT routes (not merchant API-key routes):

```http
GET /api/v1/merchant/webhook-deliveries?status=DEAD&page=0&size=20
GET /api/v1/merchant/webhook-deliveries/{deliveryId}
```

Use a trusted authenticated HTTP client with credentials injected privately;
there is no shipped frontend button/admin console. Do not put bearer tokens in
command arguments, paste full history payloads into tickets or use `curl -k`.
Resolve the actual merchant-owned public delivery ID and confirm endpoint ACTIVE.
Review ordered attempts, safe error codes and recipient remediation with its owner.
DEAD is an HTTP delivery state, not a Rabbit DLQ or a failed payment/refund.

I: after explicit merchant/incident-owner approval, use the supported request:

```http
POST /api/v1/merchant/webhook-deliveries/{deliveryId}/retry
```

Expect202 with a scheduled summary, **not** synchronous HTTP or delivery success.
Then GET history until the worker creates the next attempt and the outcome is
known. Original event/body and attempt history remain; automatic limits do not
reset. Non-DEAD/DELIVERED or disabled endpoint returns409
`WEBHOOK_INVALID_STATE`; unknown/foreign ID returns404
`WEBHOOK_DELIVERY_NOT_FOUND`. On409 reload, do not loop or bypass with SQL.
On transport ambiguity inspect state before another approved request.
Recipient effects may duplicate, including after an unknown HTTP result. The
merchant must deduplicate on event identity. Endpoint re-enable is unsupported;
do not create a replacement endpoint expecting it to inherit old deliveries.
See [the exact retry contract](api-contract.md#manual-retry).

The initial send plus five base retry delays10s/30s/2m/10m/1h use additive0–20%
jitter. Worker lease is30s, HTTP timeout5s. Preserve these and lease fencing.
Never backdate leases or fabricate HTTP status to accelerate recovery. T15's
identified worker death/stale-owner recoveries41.20/39.18s do not prove that a
merchant saw no first request. DEAD retry202 is covered by existing PostgreSQL
API/service tests in the verified T13 artifact; T17's fresh command rehearsal
covers list/detail and rejected DELIVERED retry409, not an hour-long exhaustion
or a newly measured successful DEAD retry. No shortened retry fixture is added.

## Saturation and degraded telemetry

Use fresh heap/GC, process CPU, pool active/max/pending, disk space/retention,
Outbox/delivery age and offered/completed/error/dropped work together. A pool
increase or extra replica can overload the same DB; do not blindly scale or
remove ingress limits, lease fencing, backoff or jitter. Fix the bottleneck under
an approved measured change; contain intake rather than erase backlog.

T14's frozen regular-route targets are p95<=500ms/p99<=1000ms, provider bucket
p99<=50ms, Outbox lag p99<=60s, Webhook p99/drain<=300s, heap<=85%, pool pending<=2,
soak heap-mean growth<=128MiB, zero unexpected/contract/invariant errors or drops
and no regular429. Stress policy429/drops stay distinct. The measured30-minute
soak and stress are [bounded simulator evidence](load-testing.md), not a universal
RPS/production SLO. Shared cached overdue age is not per-scenario durable lag.

For telemetry outage, restore monitors independently; business facts must not
depend on exporter availability. Require new post-recovery scrapes and a new
controlled trace, not cached UP or disappearance of an alert. Older spans may
be lost and historical telemetry is not in T16 backup. Capture safe evidence
and escalate loss of visibility; no current external paging is implied.

## Key continuity and suspected exposure

Keep the same external Webhook AES key and JWT pair across replicas, restart,
upgrade and restore; references are recorded separately from values. DB/broker/
Grafana credentials, TLS trust/certificates and decryptable backups also require
coordinated ownership. Runtime files are not a secret vault; do not regenerate
them on boot or copy lab keys into retained environments.

I: on suspected exposure, restrict access/intake as approved, preserve safe audit
evidence, identify scope and notify the repository owner. Do not repeat a secret
to demonstrate exposure or silently add a scanner exception. Merchant API-key
revocation and endpoint secret rotation exist in the
[API contract](api-contract.md); they require the owning dashboard JWT and can
invalidate callers. Endpoint secret rotation affects future attempt snapshots;
an already in-flight attempt can use the old secret. There is no dual-secret
grace or endpoint re-enable. Coordinate the recipient before approved rotation.
The supported mutation routes are `DELETE /api/v1/merchant/api-keys/{keyId}`
(204) and `POST /api/v1/merchant/webhook-endpoints/{endpointId}/rotate-secret`
(200, secret returned once). These are approval-required actions, not triage
commands; keep any returned secret in the approved private credential channel.

Changing the AES environment key alone makes retained endpoint ciphertext
undecryptable: there is no automatic re-encryption/key-ring migration. Stop and
design/test an approved migration while preserving required old keys privately.
JWT pair rotation has no demonstrated seamless overlapping-key rollout; old
access tokens may be invalidated and replicas must remain coherent. Refresh
logout is not a global session-revocation mechanism. Broker/DB credential rotation
requires a coordinated tested change, not editing `.env` in place. Restore-test
backup access after any key change; retained encrypted history still needs its
historical keys. Do not rotate away the only recovery key or claim incident
containment solely from restarting the app.

## Deployment upgrade and rollback

Use [release-checklist.md](release-checklist.md) first; current release is NO-GO.
Record exact candidate/known-good image IDs, config/key references, forward
migrations, operator access and validated DB/broker/key checkpoint. Fence intake
and drain or gracefully quiesce workers under approval. Apply only compatible
forward Flyway migrations; no historical checksum edits or Hibernate DDL.
Validate readiness, identity/auth, both consumers, financial invariants,
Idempotency replay, HMAC, telemetry and backlog before resuming intake.

Rollback is compatible **application/configuration** rollback only. T13 proved
missing-JWT configuration rejection and restoration of the same immutable image,
not rollback to an untested historical binary. Never automatically down-migrate
financial schema. An incompatible candidate needs a reviewed forward fix or an
explicitly approved isolated restore/cutover with understood data loss. T15's
one-at-a-time restart retained survivor queries but recorded230 successes and6
transients; do not promise zero-error rolling availability. See
[staging stop and rollback](staging-deployment.md#upgrade-stop-rollback-and-cleanup).

## Backup restore and incident closure

Follow [the tested DR procedure](backup-restore.md). Targets were RPO24h with
daily backups and7 daily/4 weekly retention, RTO lab15m. Native cold/mismatch
restore took101.28/109.23s; snapshot ages121.42/401.80s, not RPO zero. Separately
authenticated data/key bundles, original compatible broker node and a trusted
catalog are required. Never consume unauthenticated partial plaintext. DB,
broker and keys are coordinated; missing source facts/keys require escalation.
The lab does not provision off-host storage, schedule/retention or backup-age
alerts; these remain release gates, not unacknowledged completed services.

Require restored all-table/sequence/schema history, original key decryption/HMAC,
balanced unique postings, duplicate-safe consumers and durable drain. Keep the
old environment fenced until cutover approval; do not overwrite dev/retained
data or copy disposable `down --volumes` cleanup into an incident. Data/key
deletion and cutover loss need explicit irreversible-action approval.

Close only when the incident owner accepts the financial/recipient outcome,
fresh monitoring and drain evidence, known duplicates/loss/UNKNOWN limitations,
and a follow-up owner. Silence, HTTP202, PUBLISHED or a green diagnostic exit
alone does not close an incident. Preserve the sanitized timeline and references,
not raw secrets or mutable financial-history edits.

## Rehearsal and evidence

```powershell
python -B -m unittest discover -s ops/ci/tests -v
python -B ops/runbooks/runbooks.py check
python -B ops/runbooks/runbooks.py rehearse
```

The last command requires the exact verified image pinned in
`ops/backup/policy.json` and an idle Docker host; a different artifact is rejected.
It creates a new synthetic UUID lab with private receiver/secrets, performs one
normal API payment/refund fixture, then executes the documented read-only triage
and dashboard history/invalid-retry commands. Complete DB/schema/sequence
snapshots must remain unchanged; financial/HMAC/trace audits pass. It also requires
the real independent release-preflight outcome to be recorded. Blocked evidence
remains blocked; a passed preflight is not deployment/release authority and does
not invalidate an otherwise successful read-only rehearsal. T17's earlier
blocked outcome is historical, not a requirement to keep gates red forever.
No operational retry/repair is
automated. Only its disposable lab is removed; temporary keys/data are deleted
and cannot be recovered. Safe report is `target/ci-safe/runbooks.json`.
The contract check is also discovered by existing CI unit checks; the owned
native rehearsal is manual, not a hosted-CI/deployment result.

The historical final T17 rehearsal passed on2026-10-17,20:49:24–20:50:49 Asia/Saigon
(safe JSON stores UTC). Both dashboard list/detail returned200, DELIVERED retry
returned409, and the DEAD list was empty. All19 tables, sequences and schema
were exactly unchanged across triage and these API checks. The one synthetic
payment/refund yielded2 balanced unique postings,4 public events and2 signed
delivered subscriptions; financial/HMAC/source-trace audits passed. Both event
queues had one consumer and all four queues were empty. Real release preflight
remained BLOCKED. Project-level cleanup passed and Docker had no containers left.

178 focused controls pass on Windows and pinned Linux Python3.13.12. Final19 local
link/anchor checks (18 before the final evidence cross-link), actual controller/auth/retry/drain configuration and fixed
SQL/privacy/ownership/unavailable-source controls pass; static diff checks pass.
No Java, dependency, application configuration or wiring changed. No full Maven
suite, new security scan or T13–T16 long drill is rerun/claimed: the verified T13
artifact and its1,407-test/196-suite provenance are unchanged.

Rehearsal workload SHA-256:
`f71adf34f92a29f721523f7e6af0dcb4fb3f76ed4acb988660e0d76b71b32b96`.
Safe `runbooks.json` SHA-256:
`1b2becde388025b496c8d9b7405f2818fd92d207a9f4e5aa12d7935a799600b4`.
JAR SHA-256:
`32874cf74c358f37137f9c88d3d7f613f85f146a1628f9604b45fcf38a6f8dc7`;
image `sha256:a5c4c7ecbe6d888edad4eda95db786330a7ae412c1daf38470549165241f6275`.
Earlier rehearsals reached successful diagnosis but stopped because the fixture
override omitted an inherited queue-probe mode needed by the final audit.
Safe section comparisons confirmed no persisted changes. That mode now delegates
to the existing T16 reader, with a regression control; only the final complete
unchanged run is acceptance. No diagnostic mutation or financial repair was added.
T17 does not newly exercise successful DEAD retry202/exhaustion, live incident
remediation, key rotation, historical-binary rollback, real DLQ replay, external
notifications or a public deployment. Existing verified PostgreSQL coverage for
retry202 is in `WebhookDeliveryApiTest.retryReturns202AndOnlySchedulesWithoutResettingCountOrHistoryOrSendingHttp`.

Prior T13–T16 results are linked above and in
[the release evidence ledger](release-checklist.md#evidence-and-residual-risk).
At that T17 checkpoint, T18 was unstarted and source/image security remained
BLOCKED. Current T18 status is stated at the top; no new-image rehearsal is
claimed until its native result is recorded. No push/merge is included.
Local phase/index references are corrected separately, never committed.
