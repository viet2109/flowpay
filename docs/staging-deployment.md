# Isolated Linux/container staging (P8-T13)

## Boundary and release status

This is a reproducible **simulator-only** Linux/amd64 staging definition, not a
cloud deployment or real-money service. No provider account, domain, registry,
public API, frontend or live merchant endpoint is provisioned. Security findings
from T11/T12 remain blocking; no exceptions, suppressions or dependency upgrades
are introduced by this task. Local functional acceptance is separate from
authorization to retain/deploy a release with real data or credentials.

`ops/staging/staging.py release-check` fails closed unless current source, image,
runtime and both staging cases passed, match the immutable artifact, are <=72h
old, and source evidence matches a clean committed tree. There is no
`--skip-security` flag. Passing this preflight is necessary, not sufficient:
operator review of every infrastructure image, host, certificate, capacity,
backup and monitoring access remains required before external deployment.
In particular, T12's application-image scan is not a vulnerability acceptance
scan of all PostgreSQL/RabbitMQ/edge/proxy/monitoring images. Their pinned versions
are recorded and configuration-tested, not implicitly certified secure here.

## Topology and exposure

`ops/staging/compose.yaml` is independent of development and the T08 lab. It has
no fixed container names, external volumes/networks or shared development state.
Every image has a version and manifest digest; the application uses the exact
immutable image ID produced by [T12](container-runtime.md). Only NGINX publishes
**127.0.0.1:18443 -> 8443/TLS** (overridable loopback port). There is no plaintext
HTTP port. Retained external exposure requires a separate reviewed host/TLS/DNS
decision; do not change the bind to `0.0.0.0` as an undocumented shortcut.

| Network | Members | Boundary |
|---|---|---|
| front | edge | Only published TLS port |
| ingress | edge, app | Internal business upstream |
| data | app, PostgreSQL, RabbitMQ, topology bootstrap | Internal persistence/broker |
| observe | app, RabbitMQ, Prometheus, Grafana, Collector, Tempo | Internal operator/telemetry |
| webhook | app, Squid | Internal; no app Internet route |
| outbound | Squid only | Reviewed Webhook destination egress |

All networks support IPv6. PostgreSQL, AMQP, broker UI/metrics, application8080,
management9091, Grafana and telemetry ports are **not** published. Telemetry is
trusted private-network traffic, not public/authenticated TLS. Management binds
to all app interfaces inside these private networks; this is not isolation from
Docker/root administrators or every trusted private peer. Operators must use an
approved private access path; no public monitoring UI is enabled here.

The edge preserves T10 TCP-peer limits, safe429 bodies, stripped forwarded
headers, closed auth preflights and no mutation retries. TLS1.2/1.3, external
certificate/key and HSTS86400 are configured. Docker DNS dynamically resolves
app replicas/recreation; Prometheus discovers individual app A records rather
than double-counting the A/AAAA address of each process. Shared DB gauges still
use the T08 fresh/max policy, not sums. Per-edge rate limits are not a global
multi-edge quota. Existing T08 dashboards/rules and T09 Squid policy are reused.

Before any app producers start, `topology` provisions a non-admin app broker
user, durable exchanges/queues, all six Webhook routing keys, both Ledger success
bindings and **separate** consumer DLQs, then verifies the ten explicit bindings.
It receives the bootstrap administrator credential; the app does not. Consumers
may idempotently declare their existing topology, not create a missing durable
subscription after the first producer has already published.

## Secrets, resources and lifecycle

Supply `FLOWPAY_IMAGE` and `STAGING_SECRETS_DIR` outside the repository. The
directory contains DB_PASSWORD, RABBITMQ_ADMIN_PASSWORD, RABBITMQ_PASSWORD,
GRAFANA_ADMIN_PASSWORD, WEBHOOK_SECRET_ENCRYPTION_KEY, jwt-public.pem,
jwt-private.pem, edge-cert.pem and edge-key.pem. Files are mounted individually
read-only only into their consumers; the app imports ConfigTree and PEM files.
Never place values in Compose arguments, source, reports or logs. Preserve the
same AES key, JWT pair and retained DB/broker credentials across replicas,
upgrades and restores. Do not regenerate keys on startup. Root `.env` is neither
read nor modified by the supported smoke command.

PostgreSQL currently retains the isolated official-image bootstrap account for
both integrated Flyway and app access; that account is superuser, not a proven
least-privilege runtime role. Before retained/external use, review and separate
migration/owner/runtime privileges and test the resulting rollout. This task
does not silently change application migration credentials or claim this lab
credential design is production security acceptance.

App sandbox: non-root10001, read-only root, cap-dropALL, no-new-privileges,
256PIDs, tmpfs128MiB, 1536MiB memory/2CPU and T12 JVM budgets. Graceful stop is
120s; readiness is actual `/readyz`, not merely a running Java process. App waits
for PostgreSQL health and successful topology completion; edge waits for app
health. Rabbit/telemetry readiness does not redefine financial readiness.

| Service | Memory cap | CPU cap |
|---|---:|---:|
| app, each replica | 1536MiB | 2 |
| PostgreSQL / RabbitMQ, each | 384MiB | 1 |
| Prometheus / Collector / Tempo, each | 256MiB | 1 |
| Grafana | 192MiB | 1 |
| Squid | 128MiB | 0.5 |
| edge / transient topology, each | 64MiB | 0.5 |

One steady app stack caps total3456MiB before the transient bootstrap/checks;
two app replicas cap4992MiB. Reserve host overhead, filesystem/page cache, tmpfs
and test processes. >=8GiB is a planning baseline for two replicas, **not measured
capacity**. A light functional run on a smaller VM is overcommitted and does not
establish sustained capacity/SLOs. T14 owns load measurements; T15 owns extended
failure drills. Retention/disk/cache limits remain documented in the T08 runbook.

## Reproduce isolated acceptance

Requirements: JDK21, repository Maven wrapper, Python3.11+, Linux/amd64 Docker,
Compose with IPv6/health/completion dependencies and enough disk for images and
the advisory DB. Run gates sequentially on a constrained Docker VM:

```sh
python3 -B -m unittest discover -s ops/ci/tests -v
python3 -B ops/ci/gate.py verify
python3 -B ops/container/image.py build
python3 -B ops/container/image.py runtime
python3 -B ops/staging/staging.py smoke
python3 -B ops/container/image.py scan
python3 -B ops/ci/gate.py security
python3 -B ops/staging/staging.py release-check
```

The smoke command always uses a fresh UUID project and protected temporary
directory, explicit empty env-file and newly generated **synthetic-only** keys.
It refuses an existing project or nonempty Phase7 seed database. Test overlays
are not retained-stage configuration. `--database fresh|phase7` isolates an
iteration; final acceptance requires both cases. Owned public-looking IPv4/IPv6
merchant addresses are on an **internal** test bridge; no real external merchant
is called. The overlay also makes the proxy outbound network internal.

Checks cover actual TLS trust/hostname/protocols, private listener IP/port access
from an ingress-only client, no private DNS there, native IPv6, direct Webhook
bypass from the **actual app network namespace**, CONNECT/IP-SAN TLS through
Squid and denied private targets. These are native isolated Docker-network
proofs, **not a certification of an unspecified external production firewall**.

The API fixture exercises SUCCESS/DECLINED/UNKNOWN Payment and Refund, exact
Idempotency replays/conflict, both consumer paths, HMAC/immutable Webhook payload,
two-replica metrics discovery, provisioned dashboards and stored end-to-end trace
without credential/PII markers. Read-only SQL audits verify source/reservation,
unique balanced postings, Outbox, public events and deliveries. UNKNOWN remains
PROCESSING with its reservation; it is never converted to failure for the test.
Controlled redelivery must drain without duplicate postings/events/deliveries.

Safe evidence is `target/ci-safe/staging.json`: artifact, host/kernel/Docker/
Compose, stage/results and cleanup only. SQL snapshots, HTTP bodies, credentials,
ciphertext, raw container/config logs and temporary files are not uploaded. CI
runs the smoke between runtime and image scans, but **all security gates remain
mandatory and can keep the workflow red**. No GitHub push/deploy is performed.

## Upgrade, stop, rollback and cleanup

The Phase7 fixture is test-only code, absent from the executable JAR. It creates
a fresh V011 DB with a succeeded payment, balanced posting, encrypted subscribed
endpoint, immutable event, pending delivery and published source Outbox fact.
V001-V011 must byte-match the merged Phase7 baseline (`0b96400`, LF-normalized).
It is not a dump of user data. V012 and V013 only add nullable traceparent to
Outbox and Webhook events; they do not alter existing financial data.

Quiesce producers/workers during the schema comparison. Start the verified
candidate with its existing keys; Flyway validates/applies only forward
migrations throughV013. Compare prior migration checksums and complete financial/
endpoint/event/delivery/Outbox rows privately before re-enabling workers. Prove
the legacy pending delivery still decrypts/signs/delivers and legacy source
redelivery remains duplicate-safe. Do not truncate history, reset PUBLISHED or
rewrite failed/unknown financial outcomes to simplify acceptance.

For a retained stage, fence ingress and stop producers, drain or gracefully stop
workers, record the exact known-good image/config/key references and a restorable
DB/broker/key backup (T16 supplies its tested drill), then start the reviewed
candidate. Only resume ingress after readiness, migration, consumer, telemetry
and financial audits pass. Normal Compose `down` without `--volumes` preserves
state, but stopping a whole environment is downtime, not a rolling-HA claim.

Rollback is **application/config rollback only** after compatibility review;
never automatically down-migrate financial schema. The synthetic drill first
stops edge, rejects a candidate with a missing JWT key before financial changes,
restores the previous validated configuration with the **same immutable image**,
then verifies replay and persisted state after rollback and graceful restart.
There is no previously deployed staging binary, so this does not claim a tested
historical-binary rollback. Incompatible schema requires a reviewed forward fix
or separately tested restore with explicitly understood data loss, not guesswork.

The runner removes only its fresh UUID-owned containers/networks/volumes and
temporary synthetic keys, verifies that no project resources remain, and never
uses global prune or deletes development volumes. Do not copy its destructive
`down --volumes` cleanup to a retained environment. Docker/host administrators
remain trusted; external retained-stage provisioning is out of scope.

## Evidence checkpoint

2026-10-17: **implementation/runtime verified; security release BLOCKED**.

- Focused controls:83 tests pass on Windows/Python3.14.7 and pinned Linux/
  Python3.13.12; native actionlint, shell/Compose/NGINX/promtool/Collector checks
  pass. Focused PostgreSQL upgrade iteration:2 tests pass; final enhanced tests
  are included in the clean full gate below.
- JDK21.0.12/Maven3.9.16 `clean verify`:1,407 tests/196 suites, zero failures,
  errors or skips, real PostgreSQL/RabbitMQ Testcontainers. No unchanged full
  Java rerun after subsequent operational/docs edits; application inputs match
  verification/build evidence.
- Final `smoke` passes **both phase7 and fresh**: all native/API/telemetry,
  financial/UNKNOWN, encrypted legacy data, consumer duplicate, configuration
  rollback/restart and owned cleanup gates described above. No OOM is accepted.
  An earlier64MiB Squid startup OOM was corrected to the already verified T08
  128MiB baseline; native final runs use that cap. Fixture profile enumeration,
  domain-valid legacy IDs and full-history CI checkout were corrected as well.
- The existing T12 broker-outage runtime fixture and full Maven dependency-SBOM
  scan are not rerun here; T13's current-image staging/restart gates and fresh
  source/image scans are separate from those historical T11/T12 checkpoints.
  No dependency change or clean full supply-chain claim is inferred.
- Current image scan:299 software components (4,860 file-metadata components
  omitted from the safe inventory, not the private scan), zero application-resource
  secret detections. Fresh validated GrypeDBv6.1.10 built2026-10-16T06:29:51Z;
  native vulnerable/unavailable-DB controls pass. Of181 candidate matches,
  **6 CRITICAL +12 HIGH** remain blocking (107 MEDIUM,47 LOW,9 NEGLIGIBLE).
  Gate exit1 is a findings failure, not an unavailable-tool or clean result.
- Source rescan:804 tracked files, native secret/SAST positive/clean controls and
  complete required Java coverage pass; **10 secret-format findings,0 SAST**.
  `staging-source.json` records this source-only scope, not a substituted Maven
  SBOM gate. No exceptions/fixture rewrites/dependency upgrades. Release preflight
  remains failed; missing/stale independent acceptance evidence also fails closed.

Final JAR SHA-256:
`32874cf74c358f37137f9c88d3d7f613f85f146a1628f9604b45fcf38a6f8dc7`.
Final local image ID:
`sha256:a5c4c7ecbe6d888edad4eda95db786330a7ae412c1daf38470549165241f6275`.
Image Size170,364,948 bytes. Build/test input fingerprint:
`1fdfa5a21b9812cdd5c7484c71f07082c22fff088ae15570e710af3a81ab9f20`.
Verification records source base `7a4ffcd` plus the task's tracked test changes;
the later operational/doc commit does not claim a different compiled artifact.

Environment: Docker29.6.2, Compose5.3.1, Linux/x86_64,
kernel6.18.33.2-microsoft-standard-WSL2,4CPU,3,920,220,160 bytes RAM.
Two-replica functional acceptance was overcommitted relative to summed caps,
not a capacity test. No image/host-provider/least-privilege security clearance,
external paging, backup/restore, remote CI run or public deployment is claimed.
See the tracked roadmap; do not treat this checkpoint as permission to deploy.

Primary references: [Compose networking](https://docs.docker.com/compose/how-tos/networking/),
[NGINX HTTPS](https://nginx.org/en/docs/http/configuring_https_servers.html),
[RabbitMQ topology definitions](https://www.rabbitmq.com/docs/definitions).
