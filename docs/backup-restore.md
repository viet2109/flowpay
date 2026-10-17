# Backup, restore and disaster-recovery lab (P8-T16)

## Predeclared policy and authority

On 2026-10-17 the owner delegated the proposed recovery-target and controlled
replay decisions. `ops/backup/policy.json` records these **before acceptance**:

- Operational RPO target: at most 24 hours, conditional on successful daily backups.
- Lab RTO target: at most 15 minutes per separately named restore destination.
- Cadence/retention target: daily, 7 daily plus 4 weekly generations.
- Encrypt business/broker data and the application-key bundle separately. Retain
  them in separate access-controlled off-host stores. Keep decryption private
  keys and the trusted backup identity catalog outside both stores, with a
  separately controlled recovery copy. Restore-test every key rotation and
  backup procedure change; never discard an old decryption key while retained
  records or backups still require it.
- Original-event replay is approved **only for this invocation-owned synthetic
  mismatch lab**, after detection and a disabled-consumer reconciliation barrier.
  This is not standing approval to replay real merchant events or a DLQ.

No cloud account, external store, scheduled backup, replication or retention
deletion job is provisioned. The lab keeps its two encrypted bundles and
independent RSA recipients in separate directories under a fresh user temporary
directory, outside the repository. They are disposed after the test. This
demonstrates the procedure, not off-host confidentiality, an operating daily
schedule, a production RPO/SLA or resilience to loss of this development host.
An operator must provision/access-test independent storage and recovery keys,
schedule/alert on backup failure and age, and apply the declared retention
before relying on the operational RPO. Do not delete the last verified backup
or historical key to satisfy a count mechanically. No automated deletion is added.

## What is backed up

Producers/API and workers are gracefully stopped first, without forcing a
financial outcome. Capture a PostgreSQL 16 custom-format `pg_dump` plus an exact
all-public-table/sequence checkpoint (18 application tables and Flyway), broker
definitions and the **cold** Rabbit node directory. The quiesced lab checkpoint
coordinates the otherwise independent DB and broker snapshots. This is a
single-node lab procedure, not a distributed atomic snapshot.

The custom dump includes financial, authentication/Idempotency, Ledger,
Outbox/Webhook and attempt history. Roles are not restored: the target owns its
fresh `flowpay_stage` role; `pg_restore --no-owner --no-acl --exit-on-error` runs
against a verified empty database. No `--clean`, schema downgrade or business SQL
correction is allowed. A consistent dump alone does not recover commits after
its snapshot; there is no WAL archival/PITR. See [PostgreSQL 16 pg_dump](https://www.postgresql.org/docs/16/app-pgdump.html).

Rabbit definitions include credential hashes and the cold directory includes
the Erlang cookie: treat both as secrets inside the encrypted data bundle.
Definitions alone contain no queued messages. Physical restore is restricted to
the same pinned Rabbit 4.3.0 image, whole stopped node directory and **exact
logical node name** `rabbit@flowpay-backup-node`. The fixture supplies this
stable name on separate UUID projects, sequential private networks, not a shared
container identity. Do not rename nodes or infer cluster/quorum/stream support.
See [RabbitMQ 4.3 backup requirements](https://www.rabbitmq.com/docs/backup).

The separately encrypted key archive contains the original Webhook AES key,
JWT pair, DB/broker credentials and synthetic TLS/trust material. Synthetic
merchant client/JWT/HMAC receipt continuity state is test-only, encrypted with
that archive, not part of an application backup API. Restoring encrypted
endpoint rows with a new AES key cannot decrypt their original secrets.

## Authenticated transport and target safety

`ops/backup/backup.py` uses the already pinned OpenSSL-containing Java base
image, networkless, for CMS AES-256-GCM with independent RSA-3072 recipients.
There is no new application dependency. Catalog identity is held in the private
runner, independent of the bundles. Encryption authenticates content, **not the
sender**: accept only trusted operator-created backups with an independently
verified catalog. Restoring an attacker-authored SQL dump is unsafe even if it
can be decrypted. Archive hashes are not a replacement for trusted provenance.

Decryption writes to private quarantine; only a successful OpenSSL exit promotes
it. Failure removes partial plaintext before any tar/SQL consumer runs. Never
pipe CMS decryption into `tar`/`pg_restore`. Wrong recipient and changed GCM tag
are exercised natively. See [OpenSSL CMS](https://docs.openssl.org/3.0/man1/openssl-cms/).

The only supported command is a fresh synthetic drill, not arbitrary restore
against a supplied deployment. It generates different `flowpay-stage-<UUID>`
source/destination names, refuses existing project resources or active unrelated
containers, validates Compose topology and exact stopped broker volume labels,
and refuses nonempty database/archive destinations. Host plaintext paths must
be absolute, external and not symbolic links. Root `.env` is never read/mounted.
Archive handling rejects links, devices, absolute/Windows/traversal/duplicate
paths, file ancestors and oversized inventories **before the first extraction
write**; no `extractall` or overwrite.

One networkless ephemeral volume helper uses only CHOWN/DAC_OVERRIDE/FOWNER
capabilities to read/restore the exact cold broker volume with original Unix
ownership; no privileged mode, daemon socket or host-root mount. All other
application sandbox/network/resource limits remain unchanged. Never point this
helper at a running or foreign volume. Source networks are parked before a
new isolated destination starts; source volumes stay until final owned cleanup.

## Reconciliation and recovery procedure

1. Record the approved targets, image/JAR, DB/broker versions, original node,
   key identifiers, snapshot time, disabled consumers and queued/unacked work.
   Quiesce all writers before the consistent DB/broker checkpoint.
2. Encrypt both bundles, validate their independent identities, test decryption
   without consuming partial output, and copy to independently protected stores.
   The lab tests these locally; it does not perform that external copy.
3. Declare recovery and start the RTO clock **before** bundle authentication.
   Restore keys and data only into a new verified empty UUID destination. Start
   DB/broker with consumers and HTTP workers disabled, restore the empty DB,
   then compare every table, sequence, column/nullability/identity declaration,
   constraint definition and index to the retained checkpoint. Import the
   saved broker definitions after bootstrap and compare all definition fields
   (including credential hashes/permissions/queue arguments), ignoring only
   array ordering. Cold data supplies definitions too; the explicit import
   restores any credential re-hashing performed by the unchanged bootstrap.
   PostgreSQL may normalize a VARCHAR-array-to-TEXT-array CHECK cast into
   per-element casts when its own logical dump is parsed again (reproduced on
   pinned 16.15 with the empty migration schema). Before simulated loss, a
   distinct empty reference database receives a **schema-only restore of this
   exact source dump**. Columns/indexes, constraint inventory/enforcement flags
   and every non-CHECK definition must match the original source. Restore
   CHECK expressions must then match that trusted reference exactly; no regex
   normalization, skipped constraints or ALTER of financial source tables.
4. Validate unchanged ciphertext/key continuity, a pre-backup JWT, API-key and
   idempotent payment/refund responses. Readiness is not a recovery certificate.
   Exact checkpoint equality is required before those calls. Afterwards only
   the intentional API-key `last_used_at` audit and forward
   sequence consumption from conflicting inserts are allowed; every other
   credential/business field and completed result must remain unchanged.
5. Reconcile DB source/read-model facts and outstanding broker work before
   enabling consumers. `PUBLISHED` proves prior broker acceptance, **not** Ledger
   posting or Webhook materialization/delivery. Definitions alone can leave a
   healthy empty broker with missing asynchronous work.
6. For matching cold snapshots, resume and require drain, original IDs, balanced
   unique Ledger, signed HTTP receipts and retained completed attempts. For the
   deliberate empty-broker mismatch, first require a rejection with consumers
   still disabled; only then use the owner-approved lab action to republish the
   exact retained Outbox envelopes/IDs. Existing consumer uniqueness/deduplication
   protects prior work. The replay retains the original persistent message ID,
   existing event/aggregate headers and validated stored `traceparent` separately
   from the unchanged business envelope; it never invents baggage or forwards
   trace headers to merchants. Consumer spans may have new span IDs, but their
   retained source trace identity must match. Compare unchanged source and completed history after
   recovery. No `PUBLISHED -> PENDING`, invented event, provider call or DLQ replay.
7. Require all queues including DLQs empty, no open attempt, valid financial and
   source/delivery invariants, same-key business replay and same-image restart.
   RTO stops only after this recovery audit; backup creation and subsequent
   negative/duplicate controls are separately measured. Report actual snapshot
   age at recovery conservatively; zero missing checkpoint rows is not RPO zero
   or proof of recovering later commits. Stop/escalate on a missing key, unknown
   source outcome, incompatible checkpoint or target/provenance uncertainty.

The lab intentionally holds already-materialized pending HTTP and separately
published/unconsumed source events, in addition to completed financial/attempt
history. On the matched restore, a subsequent negative control uses an empty AES
key (startup rejection), then a wrong valid AES key (decryption fails before HTTP),
then the original key and normal jittered retry. Failed attempts are retained;
endpoint secrets are not regenerated. Another real post-restore payment/refund
workflow and original-event duplicates demonstrate continued writes/deduplication.

## Reproduction and evidence

Prerequisites: unchanged T13 clean-verified JAR/image, matching passed T15 report,
the declared Linux/Docker host envelope, idle Docker host, existing pinned helper
images, Python 3 and enough local temporary/disk space. No real merchant traffic.

```powershell
python -B -m unittest discover -s ops/ci/tests -v
python -B ops/backup/backup.py
```

The latter performs actual Docker/DB/broker/HTTPS operations; it is not part of
every small Java edit or an automatic hosted CI restore. Existing CI discovers
the new offline controls. Raw stdout/stderr, SQL rows, credentials, endpoints,
JWTs, ciphertext and envelopes stay private. Only allow-listed numeric/boolean
audits, times and artifact identities go to `target/ci-safe/backup-restore.json`.
Owned containers, networks and **disposable lab volumes** are removed, and
temporary plaintext/encrypted bundles and synthetic private keys are deleted.
They are not retained operational backups and cannot be recovered after cleanup.
The lab uses one application replica per restore on the declared WSL/Linux Docker
host, not independent-host HA. Historical Prometheus/Grafana/Tempo volumes are
not backed up here; retained source trace identity is not proof that pre-loss
telemetry or a full trace tree has been recovered.

## Final acceptance (2026-10-17)

The final unchanged native run passed, 20:11:29–20:21:42 Asia/Saigon (the JSON
stores UTC). Both freshly isolated destinations passed all19-table/sequence/
logical-schema comparisons, broker definitions, pre-backup JWT/API-key replay,
consumer recovery, source trace identity, signed HTTP and financial/history audits.

| Restored destination | RTO observed | Snapshot age at recovery | Handling |
|---|---:|---:|---|
| Matching DB/cold broker/key checkpoint | 101.28s | 121.42s | Resume original durable messages |
| DB plus definitions-only broker | 109.23s | 401.80s | Block first, then approved original-event replay |

Each initial recovery audit has3 payments/refunds,6 unique balanced postings,
12 public events,6 delivered subscriptions and6 completed attempts. Queues
including DLQs are empty. Re-publishing all12 original envelopes changes neither
stored rows nor receiver request count; original transport headers are retained.
The matched destination additionally passes a real fourth payment/refund,
missing-AES startup failure, wrong-AES no-HTTP failure, original-key normal
retry with failed attempt history retained, and subsequent restart/replay.
Wrong backup recipient and modified authentication tag are rejected before
restore, with no partial decrypted archive consumed. All owned resources are
removed and project-level absence verified; no dev state or `.env` was adopted.

162 focused Python controls pass on Windows and pinned Linux Python3.13.12,
including archive bounds/traversal/overwrite, ownership, provenance/quarantine,
key permission, source-header/privacy and mismatch-authority negatives.
Static diff checks pass. No Java, dependency, production configuration, migration
or application wiring changed: the already verified T13 JAR's1,407 tests/196
suites are reused, **not claimed as a new full Maven run**. Actual PostgreSQL,
RabbitMQ and HTTPS restore/recovery are exercised here; no H2/skipped
infrastructure test substitutes for acceptance.

Artifact identities:

- JAR SHA-256: `32874cf74c358f37137f9c88d3d7f613f85f146a1628f9604b45fcf38a6f8dc7`.
- Application image: `sha256:a5c4c7ecbe6d888edad4eda95db786330a7ae412c1daf38470549165241f6275`.
- Final workload SHA-256: `616f751b25c4ab8f3d4cd7953957386e6456813366c3842de5315493e8957796`.
- Policy SHA-256: `1315f8acb2b150578d94b551034b9289c563c898918054a1abe94b3467a5e513`.
- Safe `backup-restore.json` SHA-256: `11774f5590a498317b425924bdfbbe1799fb639b352cbee66e7621fc01ba7612`.

Observed host: Linux/x86_64 Docker29.6.2/Compose5.3.1,4CPU,
3,920,211,968 bytes RAM, kernel6.18.33.2-microsoft-standard-WSL2. The T15
bounded host envelope records actual WSL RAM; T14's exact performance approval
is not changed. The two encrypted synthetic archives were696,890/62,005 bytes
and are intentionally discarded with the lab, not retained as recovery copies.

Iteration history is explicit: early runs stopped on incomplete composite-key
snapshot ordering, inaccurate API-key audit assumptions and naive textual CHECK
comparison. Source inspection and an empty pinned-PostgreSQL dump/restore
reproduced the22 CHECK cast normalizations; the trusted pre-loss reference and
negative controls preserve strict enforcement checks. A full preliminary run
passed at100.17/112.43s. Review then hardened Linux synthetic-key readability and
original replay headers/trace identity, followed by the final complete unchanged
run above. Failed/preliminary runs are not substituted for final acceptance.

Release security remains independently **BLOCKED** by unwaived source/image
findings; a successful DR drill does not clear it. Operational daily/off-host
backups, retention service, production HA and T17 remain unimplemented. No
cloud/registry writes or push/merge are included. Phase plans/index remain local.
