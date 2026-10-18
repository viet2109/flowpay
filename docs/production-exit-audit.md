# Production engineering exit audit (P8-T18)

## Decision and scope

**2026-10-18: Phase8 DONE/FROZEN for the selected disposable Linux/container
simulator baseline.** All required implementation, operational and hosted gates
pass. The accepted executable/CI source is
`43ae0f3f26ecb36f5bead93133752e2b683b05e8`;
[GitHub run39110490452](https://github.com/viet2109/flowpay/actions/runs/39110490452)
completed SUCCESS with both `verify` and `supply-chain` successful.
Its full verification, exact-artifact image build, native runtime/recovery,
fresh/Phase7 staging and image inventory/secret/vulnerability steps all pass.

| Final evidence group | Accepted scope |
|---|---|
| Java/architecture/migrations/financial compatibility | Local clean1,407 tests/196 required suites, zero failures/errors/skips; unchanged build/test input identity; hosted full verification also passes |
| Gate regressions and privacy/portability |228 controls on Windows/pinned Linux; UID1001 native scanner/cache controls and canonical LF/strict exact spans pass |
| Source security | Clean43ae0f3,235 Maven components, zero SAST/advisory matches;10 total/10 exactly reviewed/zero unreviewed findings, current exclusive expiry and all canary/DB controls pass |
| Application image | Exact local build/runtime/scan passes;301 components, zero HIGH/CRITICAL/UNKNOWN and application-secret findings;94 MEDIUM/41 LOW/9 NEGLIGIBLE retained |
| Staging/egress/monitoring/ingress | Both local fresh/Phase7 cases, dedicated DNS/dual-stack/TLS/bypass, alert fixtures/dashboard/trace/outage/recovery and real-app ingress pass; hosted runtime/staging also pass |
| Performance | Independent declared/calibrated/approved full30-minute soak; regular route budgets and zero regular drops/429; stress13 drops/96 limits retained, no maximum-capacity claim |
| Two-instance failures | All eight cases and whole-project invariants pass; rolling270 successful reads/7 transients, no zero-downtime claim |
| Restore/runbooks | Matched/mismatch101.40/110.38s against900s lab RTO; full DB/schema/sequence/key/history/HMAC and unchanged read-only rehearsal pass |
| Cleanup/protected paths | Owned lab/scanner resources removed; no dev state or `.env` adopted; phase/index/agent/env paths absent from outgoing history |

The measured local JAR remains
`3212f15b52fb36ef9c967cb05e49b8bf897b822c0fa19b7424225f1c657be0d7`;
image `sha256:951f5e3b2cc9124eb35af64c9969a8a3999884a9b05484e9c79e533ab6bbb993`.
Build/test input identity is
`8d0e1567010aef79ad156cbb953fc542219d49c361877cff016c5257c10d0e11`.
The hosted rebuild has its own runner-generated artifact/report identities:
its passing CI is not a transfer of the local exact performance approval.
Prior declarations and rejected/interrupted reports stay historical, never
joined, relabeled or rewritten to manufacture acceptance.

Clean43ae0f3 source report SHA-256 is
`85d272b421c921460c50c8454a32b20278005ac28056e31a1bd43a41c86079a1`;
the corrected UID1001 image scan is
`b3644d463eddfd82ab0e57161ee39b07383627c63d1b5d458faff6142c2b719f`.
Local preflight passes. The final documentation-only freeze changes no Java,
POM, wrapper, Dockerfile, CI/scanner, migration, runtime config or workload input.
After its commit, regenerate the full source security report and read-only
preflight for actual clean HEAD; generated reports, not prospective wording,
record that post-commit outcome. A final hosted status report separately records
any automatic documentation-commit run. Do not hand-edit acceptance metadata.

Owner authority includes only phase-branch publication and CI observation, plus
the specifically approved existing remediation/exact false-positive metadata.
It does **not** include merge into main, image publication, cloud deployment,
purchases, real money, next-phase work or real queue/data repair. Retained/public
release remains **NO-GO** for the unprovisioned conditions below. Source reviews
expire exclusively2026-11-16T18:22:02Z and are not automatically renewed.

### Earlier hosted and local checkpoints (history, superseded above)

Hosted continuation: the owner subsequently approved publishing only the phase
branch. Commit4fa6d30 is published, not merged. Run39105546749 passes full Java,
build/runtime and staging but fails both source/image scan steps. Local passes
remain separate. A new native Linux fixture reproduces output-directory UID
denial under cap-drop ALL; matching runner UID and a narrowly initialized
empty Grype cache correct that proven portability bug.219 controls pass on
Windows/Linux. Full corrected UID1001 source/dependency and image scan gates
now PASS, with unchanged policy and real fresh-DB controls. New hosted acceptance
remains required; no unique historical hosted root cause is claimed without its reports. See
[Linux ownership correction](ci-supply-chain.md#linux-scanner-ownership-correction-p8-t18).
The subsequent hosted run39109259458 still fails source security. A fixed-code,
privacy-tested CI annotation is added for stage/category diagnosis without raw
logs. No root cause or phase acceptance is inferred from native passes alone.
Run39109618819 now identifies `source-review-count` at severity policy. Native
LF reproduction retains ten rows and all five approved content identities, but
one test-profile detector span is28–29 instead of28–28. The owner explicitly
approved canonical text snapshots and exact metadata correction. Both spans
are now separately required, one row each, under the same seventh reviewed
source location/hash/expiry; no finding is discarded or shifted automatically.
228 Windows controls pass; full canonical/native/Linux and hosted verification
remain required. Phase8 cannot be frozen merely because a fixture is known.

2026-10-18: **Verified local candidate; phase IN PROGRESS, not DONE/FROZEN.**
Final clean-committed attestation is a generated post-commit checkpoint;
GitHub-hosted workflow execution remains unobserved on the unpublished branch.
Neither may be inferred from the passing working-tree results below. Publication
requires separate owner approval; do not waive hosted acceptance to freeze.

The [reviewed candidate](dependency-security-review.md) passes fresh full Java
verification (1,407 tests/196 required suites, zero skips), exact-image build,
image severity scan, native runtime and both fresh/Phase7 staging cases.
The new full source/dependency scan passes with required current review-expiry
metadata:10 total/10 precisely accepted/zero unreviewed secret-format rows,
zero SAST/advisory matches,235 Maven components and all native controls.
Only the [owner-approved bounded source reviews](secret-findings-review.md#approved-bounded-false-positive-acceptance)
apply; no real-secret or image-secret waiver is granted.215 offline controls
pass on Windows and pinned Linux, including18 fake-Docker preflight negatives.

The independent final-artifact full30-minute load/soak, all eight two-instance
failure cases, both real restores, read-only runbook and dedicated native
egress/monitoring/alert/ingress repetitions pass with financial/HMAC/history and
owned cleanup audits. Stress13 drops/96 policy limits and rolling270 successful
reads/7 transients remain explicit, not maximum capacity or zero downtime.
Restore RTO is101.40/110.38s against the unchanged900s lab target.
Original declarations and rejected/interrupted reports are retained separately;
no time/samples are joined or historical approval rewritten. Initial native
startup/Grafana rejection causes remain unproved despite subsequent passes.

The selected target remains a disposable Linux/container simulator lab, with
no provider selected. This audit does not authorize push/merge, retained/public
deployment, purchases, real-money processing or a new phase. The independent
[release checklist](release-checklist.md) remains NO-GO.

## Earlier source checkpoint and post-commit procedure

The real full security invocation at2026-10-18T03:17:45.482371Z passes all
controls/severity policy over851 tracked/intent-to-add files. Its report hash is
`b9a9166a4a4946ec5b995ae22a44a6dc80f18d2c3f7fc7bca9303e1aae80c4aa`.
It records dirty HEAD066ceca, before final result-wording edits, not a clean
attestation for those edits. The earlier missing-expiry refusal is superseded:
actual preflight now rejects `source acceptance must match the committed release`.
Expiry remains exclusive2026-11-16T18:22:02Z; a fresh scan cannot extend it.

After committing the reviewed source/docs, run the full security gate again and
then `python -B ops/staging/staging.py release-check`. Require generated
`security.json` to name actual HEAD with `trackedWorktreeDirty=false`, complete
counts/current expiry, passing unchanged image/runtime/both staging cases and
evidence age<=72h. This document confers no post-commit result in advance;
inspect the actual generated reports. No report is hand-edited or relabeled.
An additional `phase8-local-checkpoint.json` may record their hashes/commit and
the still-unobserved hosted-CI disposition without changing tracked source.
Hosted verification requires separately approved publication; it is not
permission to merge, deploy, publish an image or change branch protection.

## Latest native operational checkpoint (2026-10-18)

The dedicated egress, monitoring/alert and real-app ingress scripts all PASS
sequentially03:13:06.323628Z–03:17:23.929491Z. Each script's content identity,
successful exit, resource absence and synthetic temporary cleanup are bound by
`final-native.json`, SHA-256
`82c290dabdf6f40695c7bc4a59db15bef68784ecdf8ed469f421fda98822b262`.
They use the verified candidate JAR/matching test classes and reviewed pinned
JRE helpers; they do not rebuild or separately scan the application image.
Monitoring exercises all versioned alert-rule fixtures plus real outage/recovery;
egress exercises real DNS/dual-stack/TLS/ACL/bypass controls; ingress exercises
auth/CSRF/CORS/rate denial/replay and two unique balanced postings. No fixture,
deadline, resource, policy or assertion was weakened. Full Java/build/test input
identity remains exact. All215 controls pass on Windows and pinned Linux.

## Initial candidate and evidence identity (before approved remediation)

The fresh verification/source-security invocations started against clean
committed source `066cecafd18769f006973cc87cc7579a164a7c73` before this
documentation/probe-diagnostic synchronization. This working-tree audit is not a
clean security attestation for a newly committed release. The only executable
edit adds fixed, privacy-safe staging assertion labels and four offline
regressions; assertions/timeouts/acceptance remain unchanged. No Java, dependency,
application configuration, migration, API, event, financial state, locking,
transaction or retry change is included.

| Identity | T18 candidate |
|---|---|
| JAR SHA-256 | `e9f600461d2d67a3a85caeb277e8ca1100e82b18411beff96945e51f9f4ec470` |
| Local Linux/amd64 image | `sha256:0e626a2e5ae3437d40ad36a3b1179a7ad0c19525ac823555d3056fcfd2f2239a` |
| Build/test input SHA-256 | `1fdfa5a21b9812cdd5c7484c71f07082c22fff088ae15570e710af3a81ab9f20` |
| Verification report SHA-256 | `1b986d87790e581741ab6e73d9eac315a4b4b7030e78ea4cfd48ec187508855d` |
| Runtime / verification JVM | Image Temurin21.0.12.1 / Microsoft JDK21.0.12, Maven3.9.16 |

Docker29.6.2/Compose5.3.1 runs Linux/x86_64 on the four-CPU WSL host, kernel
6.18.33.2-microsoft-standard-WSL2, with3,920,220,160 bytes RAM at this checkpoint.
The T14 manifest records3,920,216,064 bytes: a4,096-byte difference, not evidence
of a capacity regression, but not permission to bypass its exact-host check.
Host acceptance must still match the individual workload manifest. The image
is 170,365,022 bytes. No registry upload is performed.

The historical T13 JAR/image remain
`32874cf74c358f37137f9c88d3d7f613f85f146a1628f9604b45fcf38a6f8dc7` /
`sha256:a5c4c7ecbe6d888edad4eda95db786330a7ae412c1daf38470549165241f6275`.
Old and new JAR entry inventories and uncompressed contents compare equal;
688 ZIP timestamps differ. Their file/image hashes are nevertheless different.
Neither source equivalence nor an unchanged numeric target overrides an exact
artifact approval. No old approval, manifest, timestamp, report or JAR is
rewritten to manufacture a final pass.

Prior safe reports were preserved outside `target` before `clean`, then copied
under ignored `target/ci-safe/history-t13-t17/`. Canonical report paths contain
only the new invocation results, including rejected gates. Reports/SBOMs and
temporary build artifacts are not committed; raw source matches, logs, bodies,
credentials, ciphertext and restored business rows are not exported.

## Initial pre-remediation gate ledger

| Gate / command | Observed T18 outcome |
|---|---|
| `python -B -m unittest discover -s ops/ci/tests` | 182 controls, including four new diagnostic/privacy regressions, pass on Windows and pinned Linux Python3.13.12; no skipped controls; read-only source snapshot excludes `.env`/local instructions |
| `python -B ops/ci/gate.py verify` | PASS: actionlint; `clean verify`; 1,407 tests / 196 required suites; 0 failures/errors/skips; actual PostgreSQL/RabbitMQ |
| `python -B ops/ci/gate.py security` | FAIL at severity policy: 10 secret-format findings, 0 SAST findings; 234-component Maven SBOM; 17 unwaived HIGH/CRITICAL dependency matches |
| `python -B ops/container/image.py build` | PASS: exact verified JAR, pinned non-root image, read-only/no-network missing-prod-configuration rejection |
| `python -B ops/container/image.py scan` | FAIL: 299 software components; 0 application-resource secret findings; 18 unwaived HIGH/CRITICAL matches |
| `python -B ops/container/image.py runtime` | PASS on canonical unchanged repetition: sandbox, active-worker SIGTERM, broker-outage source/restart/replay, Ledger/Webhook HMAC, persistent keys, all13 migrations and owned cleanup |
| `python -B ops/staging/staging.py smoke` | PASS on repetition: both fresh/Phase7 cases, original migration/checksum/finance/ciphertext preservation, private IPv4/IPv6/TLS/proxy boundaries, two-replica telemetry, SUCCESS/DECLINED/UNKNOWN, duplicates, key/HMAC, config rollback/restart and owned cleanup |
| `python -B ops/load/benchmark.py accept` | REFUSED before project/workload creation: `use the frozen verified T13 artifact`; no new 30-minute soak |
| `python -B ops/resilience/resilience.py` | REFUSED before project/case creation: `unchanged verified T13 image/JAR required`; no new two-instance failure run |
| `python -B ops/backup/backup.py` | REFUSED before backup/restore creation: `unchanged T13 verified artifact required`; no new restore/RPO/RTO measurement |
| `python -B ops/runbooks/runbooks.py rehearse` | REFUSED before project creation by its historical image binding; no newly claimed native T17 rehearsal |
| `python -B ops/staging/staging.py release-check` | FAIL: source/image security is not passed despite runtime/staging success; no override |

The first runtime invocation failed at owned startup with Compose exit1 and
completed owned cleanup. Its generic failure does not establish a root cause.
It is retained as `image-runtime-startup-rejected.json`, not erased or relabeled
as a pass. Read-only subnet inspection found no fixture overlap. An unchanged
diagnostic invocation passed the complete recovery flow; the subsequent
unmodified canonical command passed after scanners finished. No runtime policy,
fixture, resource cap or acceptance check was relaxed. This is not proof that
the first failure was caused by contention or an application defect.

The initial read-only Linux unit-test launcher lacked writable temporary space
for generated test fixtures and was rejected. The complete suite then passed
with a bounded64MiB `/tmp` tmpfs and the repository snapshot still read-only;
no existing assertion or security condition was relaxed.

The first final-image staging invocation rejected an inherited monitoring
assertion after proving the legacy upgrade. The old probe reduced all inherited
assertion messages to one generic label. `staging-monitoring-rejected.json`
retains that failure. The probe now maps known assertions to fixed labels and
drops arbitrary/credential-bearing suffixes, including unknown `owned` strings;
four regression tests cover classification/privacy. It does not change
assertions, waits, scrape/trace requirements or production behavior. The native
repetition passes both complete cases, including monitoring/trace. The first
failure did not recur, so its root cause is not established by the improved
labels. Both original rejections remain visible; a later pass is not a claim
to have diagnosed their cause or permission to ignore test-stability review.

Canonical final-image safe report fingerprints: `image-runtime.json`
`e66f6c757da14ed6d2b580b843d16445180a84e00a67dd4632632938f8ebd271`;
`staging.json`
`0687eb67e3e553d35f1207c17efcedc548b56edcb52026d959bfe175f0098d66`.

Both fresh vulnerability scans use validated GrypeDBv6.1.10 built
`2026-10-17T06:27:09+00:00`. Source secret/SAST clean/adversarial controls and
vulnerable-dependency/unavailable-DB controls pass. Image dependency controls
also pass. Source dependency candidates are 6 CRITICAL/11 HIGH/11 MEDIUM/1 LOW;
image candidates are 6 CRITICAL/12 HIGH/107 MEDIUM/47 LOW/9 NEGLIGIBLE. Scopes
overlap: these are not 35 distinct proven exploitable vulnerabilities. The image
application-resource secret result does not clear source/example/test findings
or every vendor binary. See [scanner scope/policy](ci-supply-chain.md).

Fresh safe report fingerprints: `security.json`
`5ddda703b0e8daa3dc49f92c8fffdb04064c3eb9b0e447270e4633ef6e360212`;
`image-scan.json`
`2573ae6b4a5465a5056101cb568012fd6ce7d7bd66123f06f8c448549ee8df0e`.

`ops/ci/exceptions.json` remains empty. The owner's explicit choice to leave
secret-format findings blocking is preserved: no fixture rewrite, suppression,
dependency upgrade or exploitability waiver is inferred from this audit.

A post-diagnostic source-only rescan (`exit-source.json`) includes the840 tracked
files plus this explicit task audit, excluding `.env` and local instructions.
It retains10 secret-format/0 SAST findings with required main-Java coverage.
This is separate from the complete234-component Maven gate above, not a
substitute supply-chain pass or a claim that Semgrep covers every Python file.
It was captured before the final result-wording updates; this working tree is
still not a clean committed release/security attestation.

## Acceptance-criterion reconciliation

The following reviews every group in the local phase acceptance checklist.
Historical task evidence stays valid only for its declared artifact/workload;
it is not a fresh T18 operational pass.

| Criteria | Source / evidence and disposition |
|---|---|
| Phase7 frozen, remote baseline, branch and outgoing paths | Current phase branch retains merged Phase7 baseline; V001–V011 match `0b96400` after LF normalization; protected outgoing paths0; later task commits are audited separately |
| Safe prod startup, private management, live/ready outage semantics | Current full production configuration/management tests pass; final-image native runtime/staging results remain separate gates |
| Financial/Idempotency/async metrics, after-commit accounting, bounded labels | Current business/operational integration/catalog tests cover rollback, replay, provider UNKNOWN and telemetry failure; metrics are not financial truth |
| Shared DB gauges and telemetry cardinality/context isolation | Current sampler/catalog/tracing regressions preserve stale/unknown semantics, no cross-replica sum or sensitive labels |
| HTTP/durable source/consumer/worker tracing and restart | Current full tracing/restart integration coverage; V012/V013 add nullable technical traceparent only; no source envelope or signed-body change |
| Financial invariants, source/Idempotency/V1/public HMAC compatibility | Current full domain/migration/Idempotency/E2E/architecture/transaction tests pass; payment/refund/ledger/idempotency domain directories have0 differences from Phase7 |
| Monitoring reproducibility and alert firing/resolution | Final verified-JAR/reviewed-JRE native config/rule/dashboard/trace/outage/recovery repetition passes; external notification delivery is not rehearsed |
| Effective destinations, DNS rebinding, TLS, redirects and bypass | Final dedicated verified-JAR/matching-test-class real-network fixture passes all unchanged DNS/dual-stack/TLS/ACL/bypass controls; staging/image evidence remains independently bound |
| Auth/tenant/CSRF/CORS/abuse review | T10 approved marker/closed auth preflight/TCP-peer budgets remain implemented and covered by the current full suite; no cross-origin FE or global quota is added |
| Tracked CI and required infrastructure | Both hosted jobs and every required step PASS in run39110490452 at43ae0f3; workflow/actionlint/local mandatory coverage pass; owner-side required-check/branch-protection configuration is not modified |
| Source/dependency/secrets/image scanning and SBOM | Canonical LF/exact approved spans, full source/dependency and application-image gates, canaries/DB controls and clean-source/preflight PASS; no real-secret waiver or policy relaxation |
| Non-root runtime, graceful worker/key recovery | Current build/runtime/application-resource scan and application-image severity gate pass. Vendor-binary/history secret-freedom is not claimed |
| Isolated Linux staging, private persistence, bindings/DLQs, upgrade/rollback | Both current final-image fresh/Phase7 cases pass with complete owned cleanup; no schema downgrade, historical-binary rollback, production firewall or capacity claim |
| Declared load/soak targets | New independent declared/calibrated/approved candidate acceptance passes full30-minute soak and financial/HMAC/cleanup audits;13 stress drops/96 limits remain explicit, no maximum-capacity claim |
| Two-instance failure/concurrency/restart | New candidate passes all eight cases and cleanup;270 rolling reads succeed/7 transients, not zero downtime |
| DB/broker/key restore, mismatch fencing, measured RPO/RTO | New candidate matched/mismatch restores pass101.40/110.38s with all19-table/sequence/schema/key/replay audits; off-host operating RPO still unprovisioned |
| Actionable runbooks, read-only rehearsal and mutation authority | New candidate native read-only/409/unchanged-history/HMAC/cleanup rehearsal passes. No new DEAD exhaustion, real DLQ replay, key rotation or repair is authorized |
| Accurate docs, target/residual limits, protected files, final phase decision | DONE/FROZEN for Linux/container simulator lab after all required gates PASS; financial/domain/database/API contracts need no change. External release remains NO-GO; local-only phase/index/agent/env stay out of history |

## Measured limits and remaining operator conditions

The current load result is not a production SLO: regular route targets are
p95<=500ms/p99<=1,000ms; observed soak aggregate p99 is47ms and worst regular
route p99 is263.80ms on the declared one-replica simulator workload. Stress
retains13 drops and96 policy limits. Two-instance
failure evidence includes270 successful rolling reads and7 explicit transients,
not zero-downtime or multi-host HA. Detailed bounds are in
[load testing](load-testing.md) and [operational drills](operational-drills.md).

Current restore RTO is101.40s matched/110.38s mismatched against the lab900s
target; snapshot ages are121.66s/390.70s. The24h operational RPO/daily cadence and
7 daily/4 weekly retention are targets, not provisioned off-host recovery.
There is no WAL/PITR, post-snapshot commit recovery or standing real-queue replay
approval. Lab data/key archives were disposable, not retained backups. See
[backup and restore](backup-restore.md).

Retained/public deployment still needs independently protected off-host data/key
stores, scheduling/age/failure alerts/retention, external paging and staffed
response, independent monitoring/access/resource budgets, TLS/DNS/hosting and
runtime/migration DB least-privilege acceptance. Infrastructure-image security
is not established by scanning only the application image. No service/account
or frontend is provisioned, and real-money processing remains out of scope.

## Closure and future changes

All required Phase8 implementation outcomes are accepted above. Preserve the
actual hash chain, owner approvals and rejected/interrupted history. Initial
unclassified native startup/Grafana rejections remain stability limitations;
later success is not a diagnosis of their causes. Any future changed executable,
artifact/host/workload or expired security evidence needs proportionate new
verification/review, not copied approvals or weaker targets. Documentation-only
freeze uses unchanged application/CI inputs and its own actual clean-source
attestation. Real/public deployment needs the independent checklist and new
authority; no new phase is started by this freeze.
