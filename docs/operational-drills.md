# Two-instance operational failure drills (P8-T15)

## P8-T18 candidate repetition — PASS (2026-10-18)

The original T15 declaration is preserved at
`ops/resilience/history/t15/manifest.json`. The active declaration now binds the
security-remediated image/JAR and real new load-acceptance report
`1bcff0e7ea8ae5f461e2b4ccd421f438f4826ab375cf7c5538ad9248b09ff0bc`.
All eight cases, two replicas, host envelope, recovery/readiness deadlines,
outage dwell, production resources, TLS, ingress and retry/lease policies remain
unchanged. The new invocation passed; the dated T15 measurements below
remain historical, not the new result. No retained
deployment, financial SQL repair, real DLQ replay or security waiver is authorized.

New run:2026-10-18T02:45:43.254663Z–02:57:54.556326Z (about12m11s).
Report SHA-256:
`13f42a23270a47595abe8a9ea0b7b797f77524096a3ebbda3cdedadcc2d0c04e`;
executable workload SHA-256:
`736ae50b53ec68fde9894425aefdd14a30bcee162d6ef12fb3c8d43fc2309281`.

| Case | Total(s) | Current candidate observations |
|---|---:|---|
| multi_instance | 41.77 | Both replicas served business HTTP with fresh scrapes;4 original-envelope duplicates preserved all history and sent no extra HTTP |
| broker_outage | 94.39 | Source201/200/201 with4 facts unpublished while broker stopped;67.01s durable drain after restart |
| database_outage | 67.99 | App readiness503, edge504, liveness200; no financial writes offered while down; exact retained snapshot unchanged |
| worker_kill | 132.10 | Survivor recovery39.69s; unknown abandoned attempt retained, original process restarted and replay checked |
| stale_result | 75.45 | Survivor recovery39.66s; resumed old owner processed its late result without overwriting the abandoned attempt |
| slow_receiver | 29.08 | REQUEST_TIMEOUT retained, real scheduled retry delivered;14.87s drain |
| monitoring_outage | 52.61 | Four telemetry listeners unavailable while business/consumers continued; two fresh post-probe scrapes after restart |
| rolling_restart | 101.88 | Both exits143 without OOM/forced kill; survivor query200 before each restart;270 successful reads and7 explicit transients |

All eight cases pass their whole-project financial/identity/HMAC/attempt audits.
Final totals:9 payments/refunds,18 unique balanced postings,36 public events,
18 delivered subscriptions,21 retained attempts and2 expired-lease recoveries.
Owned resources/temporary keys were removed and absence verified. No timeout,
lease, jitter, retry, ingress or resource bound was changed. Case wall time is
not outage duration or a production RTO. Seven rolling transients preclude a
zero-downtime claim; one shared VM is not independent-host HA. Restore, final
native/source/clean-committed gates and public deployment remain separate.

## Scope and fixed expectations

Synthetic Linux/container staging only, not a public deployment, capacity/HA
SLO, backup/restore acceptance or security clearance. `ops/resilience` reuses the
unchanged [verified T13 image](staging-deployment.md) and requires the exact
[T14 load acceptance](load-testing.md). The T14 workload/approval are not edited.
Two app replicas share PostgreSQL/RabbitMQ and use the existing TLS ingress,
private networks, enforcing Webhook proxy and production per-instance resource
caps. Aggregate caps are not reserved physical RAM; this is a constrained,
overcommitted 4-CPU Docker VM, not proof of independent failure domains.

Expected outcomes are fixed in the harness before native execution:

| Drill | Offered work / expected HTTP | Required durable/recovery proof |
|---|---|---|
| Multi-instance | Concurrent identical create/confirm/refund requests: 201/200/201, or 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS` for the contender; exact completed replay and changed-body 409 | At least one source commit; both replicas serve business HTTP; one provider transaction/refund, balanced unique postings; original-event redelivery leaves all rows and receiver request count unchanged |
| Broker outage | Stop only the owned broker; readiness stays 200; create/confirm/refund must return 201/200/201 | Four source Outbox facts actually committed while broker stopped, then both consumers drain after restart; PUBLISHED alone is insufficient |
| Database outage | Stop only owned PostgreSQL; direct private application `/readyz` 503, TLS edge readiness 503 or504, edge `/livez` 200 | The unchanged30s pool-acquisition and edge-read deadlines can race; a gateway504 is explicitly unavailable, not successful readiness. No financial request is offered/claimed accepted during outage; after reconnect the complete retained snapshot must match, then a new financial workflow must succeed |
| Worker kill | Obtain real source confirmation 200 and an OPEN in-flight signed HTTP attempt; identify its sole owner via per-replica claim-counter delta, then SIGKILL exactly that replica | Surviving replica expires the real 30s lease, keeps unknown old HTTP outcome, retries after real jittered delay and delivers attempt2; restart original replica, exact Idempotency replay |
| Stale HTTP result | Same barrier, but pause rather than kill the owner; merchant releases its first response while owner cannot finalize | Survivor recovers/delivers attempt2; resume old owner and observe its HTTP-result counter advance; abandoned attempt stays byte-identical and global audit passes. Old result may be a buffered 2xx or a deadline/cancellation outcome; neither may overwrite history |
| Slow receiver | One merchant response waits 6s, beyond unchanged 5s client deadline | First attempt retains REQUEST_TIMEOUT, second succeeds after real retry; exact body/identity stable even if remote HTTP effects duplicate |
| Monitoring outage | Stop owned Prometheus/Grafana/Collector/Tempo; all four private telemetry listeners must be unreachable; financial workflow still requires 201/200/201 | Business/consumer effects drain without telemetry; restart stack and require two healthy scrapes timestamped after the recovery probe began, plus business HTTP metrics again. Old cached UP samples cannot pass; no lost-trace replay claim |
| Rolling restart | Same image/config; stop/start one replica at a time while querying TLS ingress; GET permits 200 or explicit transient transport/502/503/504 | Before restarting each stopped replica, survivor must serve readiness and a valid business query through the same TLS edge. Each stopped process exits0/143 without OOM/forced kill, two replicas regain health, a new financial workflow succeeds after each restart; record all query successes/transients |

Rolling queries never hide transient failures or retry financial mutations.
The existing edge has upstream retry **off**; this does not promise zero-error
rolling availability or Kubernetes-style readiness-aware routing. A slow/unknown
provider outcome is not forced to FAILED: these drills use SUCCESS simulators,
and inject failures at explicitly described persistence/delivery boundaries.
Source/provider mid-flight ambiguous financial reconciliation is not added.
Monitoring recovery here proves fresh application scrapes and the stopped
telemetry services running again, not end-to-end receipt of every trace/log or
external alert notification. Missing spans during exporter outage are not
reconstructed and trace retention/completeness is not a financial invariant.

The receiver subscribes to `payment.succeeded` and `refund.succeeded` only, so
there is exactly one candidate HTTP send at the process-barrier point. All four
source facts per complete workflow still materialize as public events. One
endpoint produces two deliveries/workflow; Ledger and Webhook consumers remain
independent. Claim ownership is measured before injecting a fault, not guessed
from container order. If the in-flight barrier is missed, that drill fails.

No application timeout, pool, lease, retry/backoff/jitter, locking, schema,
transport, financial status or ingress budget is changed. The held merchant
barrier has a 90s fixture bound; app HTTP remains 5s. Recovery/readiness have
predeclared 180s lab bounds, outage dwell is8s, rolling read traffic has a240s
bound. These prevent runaway tests; they are not production RTO/SLO promises.
No backdated timestamps, injected business rows, history reset, source-state
repair, provider reconciliation, PUBLISHED reset or DLQ replay is performed.
Only already retained original envelopes are deliberately republished in the
controlled deduplication drill, never invented replacement event identities.

## Commands, authority and evidence

```sh
python3 -B -m unittest discover -s ops/ci/tests -v
python3 -B ops/resilience/resilience.py
```

Requirements: Docker Linux/amd64, the exact verified JAR/image and successful
matching T13/T14 evidence in `target/ci-safe`. The runner validates the frozen
T13 runtime-config hashes and a separately predeclared T15 host envelope:
Linux/x86_64,4CPU,3-4GiB Docker VM, pinned kernel/Docker/Compose versions. Actual
MemTotal is recorded for every run; a4096-byte WSL reporting change must not
rewrite/reuse T14's exact-host performance approval. T15 is not a performance
comparison and uses two replicas, not T14's one. The runner refuses any other active container at entry; it
does not stop/adopt unrelated workloads. Its UUID project must be absent before
creating resources. An explicit empty env-file prevents root `.env` adoption.
Temporary synthetic signing/encryption/JWT/broker keys stay outside the repo.

The private probe/control channels retain credentials, public IDs, original
envelopes, HTTP snapshots and SQL rows only inside fixture/runner RAM or owned
temporary files. Report `target/ci-safe/resilience.json` contains fixed case
names, counts, safe booleans, artifact/workload hashes and durations only.
No raw HTTP/SQL/container logs, credentials, ciphertext, signatures or digests
of signed payloads are published. All SQL audit/snapshot probes force read-only
transactions and10s statement timeout; the harness never edits financial data.

After each drill, require exact payment/refund counts, refund capacity, one
provider attempt, balanced unique Ledger postings, original Outbox/public-event
identity, complete delivery/attempt history and no stuck lease/Idempotency row.
Compare private complete source/financial snapshots across DB outage and
controlled original-event duplicates. Compare the abandoned attempt across the
late worker result. HMAC verification and stable exact body apply to every send.
Repeated HTTP attempts are permitted and retained, not erased. Receiver request/
identity observations are checked privately; raw per-request receipts are not
exported and persisted attempt count is not an exactly-once HTTP guarantee.

Failures replace any stale pass report, preserve bounded diagnostic stage and
completed-case evidence, and still unpause/clean only the invocation's owned
project. Cleanup removes disposable containers/networks/volumes/temporary keys;
it must never be copied to a retained stage or dev database. The normal success
path also verifies resource absence. No global prune, registry/cloud write,
public service account, paid provider or real merchant is involved.

These operational tests are separate from Java regressions and source/image
security gates. An unchanged verified artifact does not require rerunning an
unchanged full Java suite; application/wiring changes would require full clean
verification. CI automatically discovers the offline Python controls, but this
bounded manual drill is not silently added to the normal verification-job
budget or treated as hosted-CI acceptance. Security exceptions remain empty;
release stays BLOCKED. T16 backup/restore is not part of this task.

## Verified checkpoint (2026-10-17)

**P8-T15 DONE for these declared synthetic operational drills**; independent
release security acceptance remains BLOCKED. Final unchanged harness run:
09:23:10.228555Z-09:31:16.359248Z, about8m06s including bootstrap and cleanup.

| Case | Total(s), including verification | Observed proof |
|---|---:|---|
| multi_instance | 27.14 | Two freshly scraped replicas served business HTTP;4 original envelopes republished, all queues including DLQs empty, full immutable snapshot/request-count comparison unchanged |
| broker_outage | 40.42 | 201/200/201 while stopped;4 unpublished facts retained;21.20s drain after broker start |
| database_outage | 49.62 | Application readiness503, TLS gateway504, liveness200; zero financial calls offered while DB down; full retained snapshot unchanged after reconnect, then new workflow succeeds |
| worker_kill | 81.76 | Source confirmation200; survivor recovery41.20s, first attempt LEASE_EXPIRED with null HTTP status/duration, second delivered; original process restarted and replay verified |
| stale_result | 64.81 | Survivor recovery39.18s; old owner resumed and produced HTTP outcome; abandoned attempt stayed byte-identical, no stale overwrite |
| slow_receiver | 24.21 | REQUEST_TIMEOUT retained, real retry delivered;16.70s drain |
| monitoring_outage | 39.09 | All4 telemetry listeners unavailable while business/consumers succeed;2 post-probe fresh scrapes and business metrics after restart |
| rolling_restart | 85.49 | Both exits143, restart/verification38.30s and39.98s; survivor business query200 before each restart;230 successful queries and6 explicit transients |

Totals are case wall time, not advertised outage duration, drain latency or RTO.
The receiver deliberately observes at-least-once effects; no exactly-once claim.
Final whole-owned-project audit:9 payments/provider transactions,9 successful
refunds,18 unique balanced Ledger postings,36 original public events,18 delivered
subscriptions,21 retained attempts including2 recovered expired leases. Every
case's exact counts, invariant audit and receiver HMAC/immutable identity pass;
no lingering PROCESSING Idempotency, OPEN attempt, lease, backlog or DLQ remains.
Expected SIGKILL/pause and temporary dependency stops are not treated as crashes
of unrelated resources. Healthy final services and no OOM are required.

134 focused Python controls (20 added for T15) pass separately on Windows and
digest-pinned Linux Python3.13.12. Native Compose/configuration, real TLS/API,
PostgreSQL/RabbitMQ/process/network and recovery checks above pass on the same
verified image. No Java/source/resource/POM/CI-workflow, T13 runtime definition
or T14 workload/approval changed, so the unchanged T13 verified1,407-test artifact
is reused; no new full Maven run or source/image security scan pass is claimed.
Static diff checks pass. No paid/external account, cloud deployment or T16 work.

JAR SHA-256:
`32874cf74c358f37137f9c88d3d7f613f85f146a1628f9604b45fcf38a6f8dc7`.
Image:
`sha256:a5c4c7ecbe6d888edad4eda95db786330a7ae412c1daf38470549165241f6275`.
Final executable workload SHA-256:
`97b75f2a53e01b89ffa474b861373233e9c26d05abf2f1c9dabc131a31cf7f4f`.
Safe `resilience.json` report SHA-256:
`3b9b63e2f96bc64267ea21b099e3b9daf16ace7348bab584f0a6510316465c04`.
The exact prerequisite T14 report hash is in `manifest.json`; its approval and
performance results remain unchanged. Hardware observed in this T15 run:
Linux/x86_64,4CPU,3,920,220,160 bytes Docker RAM, kernel
6.18.33.2-microsoft-standard-WSL2, Docker29.6.2/Compose5.3.1. Two app replicas
retain T13's per-instance1536MiB/2CPU cap and768MiB heap setting; other service
budgets, secrets, listener/proxy and backoff policies are unchanged.

Iteration history is not hidden: an initial native run stopped at an overly
strict DB-edge readiness expectation; separate application/edge probes confirmed
503 versus504 from unchanged30s pool/proxy deadlines. A preflight also rejected
a4096-byte WSL MemTotal change. T15 then declared its own bounded3-4GiB host
envelope, recording actual RAM rather than changing T14's exact-host approval.
A complete preliminary run passed; review added fresh-scrape and survivor-query
requirements and negative controls, followed by the full final unchanged run
reported above. Preliminary results are not substituted for that final run.

Owned containers/networks/volumes and temporary keys were removed and absence
verified. User `.env`, other resources, ignored phase/index plans and agent
instructions are not committed or adopted. Commit only task-scoped tracked
implementation/docs on the existing phase branch; no push/merge is included.
No host/provider HA, zero-error rolling availability, provider-call crash
reconciliation, DB-write-during-outage rejection contract, permanent receiver
DEAD path, complete trace/log recovery, external paging, backup/restore or clean
release security acceptance is established by these bounded drills.
