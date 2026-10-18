# CI verification and supply-chain gates

## Authority and scope

P8-T11 adopts the sole pre-existing user-owned `.github/workflows/ci.yml` with
the user's explicit confirmation on 2026-10-15. `.env`, local agent instructions,
`docs/README.md` and `docs/phase-*.md` are not adopted. The phase branch remains
local until publication is separately requested. No runtime dependency upgrade,
API/schema change, deployment, merge or release is added. T12 extends the local
image build/scan checks as described below; registry publication remains absent.

P8-T18 later received explicit owner approval to remediate existing runtime
dependencies and the application base image. The version/advisory rationale and
fresh verification status are in the [security review](dependency-security-review.md).
This does not change the policies below: no exceptions, source-fixture
suppression, scanner downgrade or automatic release follows from that approval.
The pre-acceptance candidate's fresh full Maven scan finds zero advisory matches in235
components; source security then failed on10 secret-format findings, with zero
main-Java SAST findings and native controls passed. Its301-component image scan
passes the unchanged severity policy, retaining94 MEDIUM/41 LOW/9 NEGLIGIBLE.
Neither result is a clean committed release attestation or Phase8 completion.

2026-10-18: [individual source-finding review](secret-findings-review.md)
reproduces10 `generic-api-key` findings at seven locations in five files and
documents their test/example/public-prefix roles. That initial review did not
change the no-exception decision or detector: classification alone was not an
executable waiver. Later on the same local date, the owner explicitly authorized
the [bounded acceptance](secret-findings-review.md#approved-bounded-false-positive-acceptance)
of exactly those source false positives. Verification results are recorded in
that review, not inferred from approval. Real/new/unreviewed findings still block.
The subsequent full security invocation PASSes with10 total/10 accepted/zero
unreviewed detector rows, zero SAST/advisory matches and235 Maven components;
native canaries/clean/vulnerable/unavailable-DB controls pass.203 offline controls
pass on Windows/Linux. This working-tree result is not a clean committed release
attestation: that checkpoint's release preflight rejected the dirty candidate, and
final artifact-bound operational acceptance remains open.

Later release-time expiry enforcement requires fresh source evidence containing
the earliest accepted-review deadline; the current preflight rejects the older
report missing that metadata.212 controls passed on Windows and pinned Linux
before native-script preflight additions. The latest215 controls now pass on
both Windows and pinned Linux, including18 fake-Docker rejection cases and
both actual read-only release-preflight outcomes without deployment authority.
A new full native source/dependency scan now PASSes with complete counts and
exclusive earliest expiry;851 tracked files,235 Maven components, zero SAST/
advisory matches,10 total/10 accepted/zero unreviewed rows and all native controls.
It records the dirty source checkpoint at2026-10-18T03:17:45.482371Z, not a clean
release. After commit, require another real full scan naming actual clean HEAD
and the independent release preflight. No earlier report is patched into acceptance.

CI runs on pull requests, pushes to main/phase branches and explicit manual
dispatch. Both `verify` and `supply-chain` must succeed. Repository branch
protection/required-check configuration is an owner-side setting, not changed
by this task. Do not use `pull_request_target` or a privileged self-hosted runner
to execute untrusted PR code. Hosted jobs receive only `contents: read`, checkout
does not persist credentials, and no PAT, application secret, OIDC, package-write
or security-events-write permission is required.

## Reproduce

### Linux scanner ownership correction (P8-T18)

Owner-authorized publication of commit `4fa6d30` produced hosted
[run39105546749](https://github.com/viet2109/flowpay/actions/runs/39105546749):
full verification, image build, runtime and fresh/Phase7 staging pass, but both
source security and image scanning fail. Their raw logs/artifacts remain
unread without authenticated GitHub access; the exact hosted failure cause is
not inferred from exit1 alone.

A separate native Linux-volume reproduction finds a real portability defect:
with `--cap-drop ALL`, default root cannot write a0755 output directory owned
by runner UID1001. Both pinned Gitleaks and Syft reject that fixture; using its
owner UID writes the report. Windows Docker Desktop binds had hidden this
POSIX ownership difference. Scanners now explicitly use the actual POSIX
runner UID/GID. They retain read-only roots, no-new-privileges, all capabilities
dropped, unchanged source mounts, detector controls, severity policy and
default network isolation. The private bounded `/tmp` tmpfs declares sticky
mode1777 explicitly instead of inheriting an image-specific directory mode;
Grype's image otherwise rejects UID1001 temporary-file creation. Windows keeps
its existing non-POSIX identity behavior.

Each newly created/label-verified Grype cache is assigned only to that runner
before use. A short-lived pinned Python helper (the same image already used
for Linux controls) receives only the empty owned volume, no network, source,
credentials or Docker socket. It has only CHOWN in addition to cap-drop ALL,
sets0700 and changes that directory's owner, not recursively. Scanners never
receive CHOWN or DAC overrides. Wrong labels, nonempty caches and failed
initialization fail; the existing exact-owner cleanup still applies.

219 offline controls pass on Windows/pinned Linux; actionlint and diff checks
pass. Native UID1001 Gitleaks/Semgrep/Syft controls verify report creation,
continued canary detection and cache writes. Full UID1001 source/dependency and
image gates now PASS, including real fresh DB download, vulnerable/unavailable-DB
controls and unchanged severity policy. Source235 components have zero SAST/
advisory matches;10/10/0 exact source finding accounting/current expiry pass.
A new hosted run must still pass before phase closure. No application/build/test
input changed: preserve the verified JAR/image and original load/failure/restore
hash chain rather than rebuilding or relabeling operational evidence.

The first corrected hosted run39109259458 still rejects source security; native
passes do not establish its cause. CI failures now emit a fixed-code annotation
containing only an allow-listed stage and reason category, readable through
public check metadata without exposing logs or report contents. Unknown stages,
arbitrary errors and newline/annotation-injection inputs map to generic codes.
No annotation is emitted for a pass or outside GitHub Actions. This diagnostic
does not change exit status, policy, controls, permissions or acceptance.

Requirements: JDK 21, Python 3.11+, Git, Bash (Git Bash on Windows), Linux Docker daemon and outbound access
to Maven Central, official release/image endpoints and the Grype database.
Execute from the repository root:

```sh
python3 -B -m unittest discover -s ops/ci/tests -v
python3 -B ops/ci/gate.py verify
python3 -B ops/ci/gate.py security
```

The verification gate invokes the checked-in Maven wrapper with `clean verify`,
not an allow-list of tests. Optional OTLP metric export is disabled; PostgreSQL
and RabbitMQ remain real Testcontainers infrastructure. A Linux Docker preflight
must pass. Every non-abstract source matching Surefire's conventional test names
must have a fresh, nonempty passing report. Missing reports, old reports, failed,
erroneous or skipped suites/testcases fail, including new source tests added
later. This intentionally forbids silently disabling infrastructure coverage.
Nested suites contribute counts; abstract test support is not an executable
suite. If a legitimate new discovery convention is introduced, review/update the
inventory logic and its negative regressions together rather than weakening it.

On Windows use `python` instead of `python3` and set `JAVA_HOME` to the JDK 21
installation. Preflight checks both PATH Java and Maven's actual JVM, since a
pre-existing IDE Java 17 `JAVA_HOME` can differ from PATH. `FLOWPAY_CI_MAVEN` may point to an
existing Maven executable for local reproduction; it is not set in CI. Do not
inject test exclusions, `skipTests`, `maven.test.skip`, or scanner bypasses through
Maven options/settings. CI explicitly clears `MAVEN_ARGS`/`MAVEN_OPTS` and has no
shared writable Maven cache. The wrapper distribution remains the repository's
Maven 3.9.16. GitHub's current supported Temurin **21** patch is resolved and its
vendor signature checked by the SHA-pinned setup action: the JDK patch/hosted
runner image can advance, unlike the pinned scanner tools. This is reproducible
procedure, not a claim of bit-identical JDK/runner/artifacts forever.

## Tool selection and bounded coverage

`ops/ci/tools.json` records exact tool versions and scanner manifest digests;
workflow actions are pinned to full commit SHAs with version comments. Reviewed
release/registry metadata was resolved on 2026-10-15. Digest/checksum pinning
prevents floating-tag replacement; it is not proof that upstream code is safe.
Pin changes require release/advisory review, negative controls and rerunning the
affected gates. No curl-to-shell installer or floating scanner action is used.

| Tool | Purpose | Deliberate boundary |
|---|---|---|
| actionlint 1.7.12 | Native workflow syntax/expression validation, archive SHA-256 checked | Shell/Python secondary linters disabled; gate scripts have separate tests |
| CycloneDX Maven 2.9.3 | Resolved transitive compile/runtime/provided/test dependency graph, CycloneDX JSON 1.6 | Not an OS/image inventory or complete build-plugin/annotation-processor inventory |
| Grype 0.120.1 | Scan Maven SBOM against current advisory database | Known published package advisories, not proof of exploitability/absence of unknown flaws |
| Gitleaks 8.30.1 | Built-in secret detectors plus a nonfunctional regression canary | All tracked working-tree files; not untracked `.env`, ignored local docs, or Git history |
| Semgrep CE 1.181.0 | Seven local Java SAST rules | Main Java source only; not comprehensive interprocedural taint analysis or a manual security review replacement |

T12 extends `verify` after clean Java verification with exact-JAR image build,
isolated prod runtime/restart recovery, and an image security gate. A successful
build is required for runtime/scan; image scan still runs after a runtime failure
so its evidence is not hidden. The independent source supply-chain job remains
mandatory. Safe explicit image reports/inventory join the seven-day artifacts;
no image archive, secret or raw logs are uploaded. Syft1.45.0 and Grype catalog/
scan the actual saved image's OS and Java inventory without socket mounts.
See [container gates and limitations](container-runtime.md); no image waivers
or registry credentials are added.

T13 adds the isolated TLS staging smoke to `verify`, after production-image
runtime and before the image scan. Both fresh and Phase7-upgrade cases must
pass; only `staging.json` joins the explicit safe artifact list. This uses
synthetic-only external keys and private owned resources, not a hosted stage.
The image scan still runs after a staging failure and the independent source
security job remains mandatory. A local functional pass cannot override either
security gate. See [staging boundaries and reproduction](staging-deployment.md).
The verification checkout fetches complete Git history so V001-V011 can be
compared to the merged Phase7 baseline; credentials are still not persisted.
This does not extend the tracked-source secret scan to Git-history scanning.

The Java rules cover process execution, concatenated SQL at selected statement
sinks, native object deserialization, common trust-all TLS APIs, weak digest/
cipher construction and unsafe default YAML loading. They are versioned local
rules, without registry downloads, service login, metrics, inline `nosemgrep`
suppression or directory-wide test/doc waivers. The secret scan includes tracked
tests, documentation, scripts and CI. Runtime-created fake canaries are never
committed credentials. T12 adds image inventory/application-resource checks
described in [container runtime](container-runtime.md). Git-history scanning is
still separate; a fresh source/image gate cannot prove old history secret-free.

Scanner containers have read-only roots/source, dropped capabilities, no-new-
privileges, finite CPU/memory/PID/tmpfs limits and no Docker socket. Gitleaks and
Semgrep have no network. Grype gets only package inventory/config/its disposable
database directory, not source, secret reports, `.git`, host credentials or Maven
settings. Only public package coordinates are submitted to this local scanner;
no SaaS analysis is invoked.

## Failure and exception policy

- **Dependencies:** HIGH, CRITICAL and UNKNOWN block, whether or not a fix exists.
  MEDIUM/LOW/NEGLIGIBLE findings remain visible for maintainer review; absence of
  a fix is not a waiver. Findings already present before T11 are labelled baseline
  during triage, not silently suppressed or mechanically upgraded.
- **SAST:** every finding in this deliberately security-only rule set blocks.
- **Secrets:** every unreviewed finding blocks. Remove/rotate real material;
  never print matches or weaken detection to make fixtures green. The owner's
  P8-T18 approval permits only the seven individually reviewed synthetic/public
  source locations in `ops/ci/secret-reviews.json`, not real-secret exceptions.
  All ten detector rows remain reported; accepted/unreviewed counts and review
  IDs are distinct. Source scans ignore inline `gitleaks:allow` instructions.
  Image scans do not use source acceptance.
- **Tool/report/DB failure:** blocks, never a clean scan. Missing, malformed,
  incomplete/empty-coverage reports, scanner errors/timeouts, missing Docker,
  failed downloads, invalid pin/checksum and stale/future-dated DBs cannot pass.

`ops/ci/exceptions.json` starts empty. A dependency/SAST exception must match one
exact emitted `key`, with named `owner`, explicit `approvedBy`, rationale,
review/evidence reference, `reviewedOn` and `expiresOn`. Maximum duration is
30 days, expiry is exclusive, and future-reviewed, expired, duplicate, blanket,
secret or no-longer-matching entries fail. **Do not add an exception without
owner approval**, even for baseline findings. The project maintainer owns
triage and expiry follow-up; approving exceptions/branch protection requires
human review. This file checks recorded approval, not the reviewer's identity
cryptographically. Never put secrets or sensitive analysis into exception text.

`ops/ci/secret-reviews.json` is a separate bounded source-review manifest, not a
Gitleaks ignore/baseline or a dependency/SAST waiver. It binds the pinned scanner
image/config, exact rule/path/single line, whole-source-file SHA-256 after LF
normalization, expected finding multiplicity, owner/approval/reason and existing
review document/anchor. At most seven entries/ten rows are authorized, restricted
to test sources or tracked documentation; main/prod/local files, globs and hidden
paths are forbidden. The checksum is public source-file identity, not a secret
digest. Changed files (including elsewhere in the file), changed counts, stale
unmatched entries, duplicate entries, missing evidence, malformed/future/expired
or over30-day approval fail closed. CRLF/LF checkout normalization alone does
not invalidate approval. No automatic renewal or fingerprint refresh exists.
Recorded reviewer names require actual human review; metadata is not a signature.
The approval expires2026-11-17 01:22:02 UTC+07 exclusively. Renew/remove it through
owner review, never by disabling a failing check or accepting future secrets.

Safe source evidence also records the earliest exclusive expiry among accepted
reviews (`acceptedSecretReviewExpiresAt`). Release preflight rechecks that
deadline against its current clock and requires complete total/accepted/
unreviewed accounting, independently of its72-hour scan freshness allowance.
A fresh report cannot extend a review; missing, malformed, timezone-naive or
expired review deadlines fail closed. Earlier source reports lacking this
metadata must be regenerated by a real scan, not manually patched.

No shared scanner DB/cache is restored in CI. Each scan uses a unique labelled
disposable Docker volume for SQLite on Linux storage, including on Docker
Desktop; do not bind-mount its database onto Windows/NTFS. Ownership is checked
before removal. Timed-out scanner containers have unique names/labels and only
that exact owned container may be stopped; never clean unrelated Docker data.
Each scan must check for updates,
validate DB integrity and enforce maximum DB build age **72 hours**. Downloads
and commands have finite timeouts; jobs are bounded to 50/25 minutes after the
T12 image checks extended the verification job. Network,
rate-limit, registry and advisory-data outages are failures: rerun after repair,
do not turn off age/update/hash checks or label an unavailable scan clean.
Local Maven caches are only acceleration, not accepted scanner evidence.

Each security invocation runs native secret/SAST positive and clean controls,
then scans the actual snapshot. Dependency controls use a synthetic vulnerable
Log4j 2.14.1 inventory (no vulnerable executable is run), require detection of
CVE-2021-44228, and require a missing/offline DB to fail. Unit regressions cover
missing/skipped/stale test coverage, scanner error/timeout/malformed reports,
DB age and exact/expired/unapproved exceptions.

## Evidence and privacy

Only these explicit paths may be uploaded, retained seven days:

- `target/ci-safe/verification.json`: aggregate counts, source commit/dirty flag,
  workflow result and executable JAR SHA-256; no raw test names/logs.
- `target/ci-safe/security.json`: tools/pins, gate/control status, DB build/schema,
  package advisory/SAST identities/severity/exception status, secret total /
  accepted / unreviewed **counts**, source-review IDs and earliest exclusive
  review expiry, never matched values.
- `target/ci-safe/sbom.cdx.json`: allow-listed package coordinates and dependency
  graph, without authors, source paths, arbitrary properties or external URLs.

Never upload raw Surefire XML (its `system-out` may contain fixture credentials),
scanner matches/snippets/SARIF, Maven/settings logs, `.env`, temporary extraction,
analysis or the entire `target/` directory. Subprocess output is captured privately,
not echoed; disposable raw reports are removed on normal exit. Job termination
can leave private runner/temp data until hosted runner disposal; never run this
workflow in a shared persistent privileged environment. A failed invocation
replaces prior success evidence and never reuses an old SBOM as fresh evidence.
For diagnosis, reproduce locally with authorized access and inspect only safe
metadata; raw logs must not be copied into chat, issues or public artifacts.

## P8-T11 execution record

The user approved workflow adoption, then explicitly chose **no exceptions;
leave the secret gate blocking and review later** on 2026-10-15. The policy file
remains empty. No legitimate fixture or detector was rewritten to manufacture
green security acceptance. Ten baseline detections occur at seven locations in
five unchanged files; repeated detector results are not ten separate credentials:

| Tracked file | Line(s) at entry | Triage context (not secret values) |
|---|---|---|
| `docs/api-contract.md` | 637 | Example Idempotency-Key, not an authentication credential |
| `src/test/java/com/flowpay/backend/infrastructure/config/ProductionEnvironmentPostProcessorTest.java` | 139, 159 | Fixed synthetic key in mock production-environment validation fixtures |
| `src/test/java/com/flowpay/backend/merchant/domain/ApiKeyTest.java` | 14 | Public key prefix, not the raw API secret |
| `src/test/java/com/flowpay/backend/webhook/infrastructure/security/AesGcmWebhookSecretCipherTest.java` | 15, 62 | Synthetic test encryption keys; one is deliberately wrong |
| `src/test/resources/application-test.yaml` | 28 | Test-profile-only synthetic encryption key |

These descriptions are source-based triage, **not a scanner waiver**, proof that
every possible detector finding is harmless, or approval for production reuse.
The project maintainer owns later review; no suppression is enabled. The known
baseline is separate from task-introduced implementation errors. No real `.env`
values were inspected, printed or submitted to scanners.

Local focused controls and full Java verification are recorded at the final
checkpoint below. A local component run is not a GitHub-hosted workflow run.
No push, remote dispatch, branch-protection change or T12 image work is implied.

Final local checkpoint, 2026-10-15:

- **28 gate unit tests pass on Windows and Linux** (pinned Python container);
  actionlint 1.7.12 passes natively. Setup-java's signature/version inputs were
  verified against the exact pinned action revision.
- Native Gitleaks built-in/custom canaries, seven Semgrep rules and clean controls
  pass. Main-Java scan covers all required files and reports no SAST finding.
- Native dependency canary detects the Log4j advisory and exits nonzero; an empty
  offline DB also fails. Actual DB metadata in this release is nested under
  `descriptor.db.status`; it is cross-checked with the official offline `db status`
  command reading the same invocation-owned volume. Missing/mismatched/invalid/
  stale status fails; parser shape mistakes are not accepted as clean scans.
- CycloneDX resolves **234 components**. Grype reports **29 package/advisory
  matches: 6 CRITICAL, 11 HIGH, 11 MEDIUM, 1 LOW**, no ignored/excepted matches.
  The database is schema **v6.1.10**, built **2026-10-15T06:30:36Z**. These are
  candidates for maintainer/advisory/reachability review, not 29 proven exploitable
  flaws. The 17 HIGH/CRITICAL matches independently block alongside the ten secret
  findings. Affected package families include Spring WebMVC, Tomcat, Rabbit client,
  Jackson, Netty and PostgreSQL driver. No runtime/test dependency changed in T11.
- **Full Java `clean verify`: 1,405 tests/195 suites, zero failures/errors/skips**,
  real PostgreSQL/RabbitMQ. The actual reports also pass the new fresh-source-suite
  inventory checker, requiring all 195 suites. No test exclusions or H2 substitution.
  This local full run used Microsoft JDK **21.0.12**, Maven **3.9.16** on Windows;
  an earlier launcher attempt used the IDE's Java 17 `JAVA_HOME` and failed before
  reports. That rejected attempt is not acceptance. Actual-Maven-JVM preflight
  and its negative regression now prevent this mismatch. Subsequent CI-only/docs
  edits do not justify rerunning an unchanged full Java suite.
- Executable JAR: **79,498,273 bytes**, SHA-256
  `c023f4f329ea8ccb5478419789d6f94aafbaee64bfa81be1707c27898cc00cb9`.
  Docker **29.6.2**, Linux/amd64, **4 CPUs / 3,920,220,160 bytes RAM**. All labelled
  disposable scanner containers/volumes are confirmed absent after execution;
  existing development data and earlier-phase fixtures were not removed.
- Final security invocation reaches severity policy with all native controls
  passed, then correctly **fails**. Safe JSON retains all public advisory identities
  and blocking flags, only the secret count, and the projected SBOM fingerprint.
  The owner-deferred findings remain unwaived; **implementation complete does
  not mean security accepted**, phase complete or ready to publish/deploy.

The full Java run, native report checker, workflow validator and scanner components
were exercised locally; this is not a successful GitHub-hosted workflow run or
attestation for a published commit. Remote jobs/required branch checks must still
be observed after an explicitly authorized push. Initial NTFS-bound DB timeout
and a report-adapter shape error were rejected and corrected before the final
native controls; neither was silently waived. CI uses invocation-owned Linux
volumes and fresh data, not preserved diagnostic caches.

## Primary references

Tool behavior/pins were checked against the official [CycloneDX Maven goal](https://cyclonedx.github.io/cyclonedx-maven-plugin/makeBom-mojo.html),
[Grype configuration](https://oss.anchore.com/docs/reference/grype/configuration/),
[Gitleaks](https://github.com/gitleaks/gitleaks), [Semgrep local CLI](https://semgrep.dev/docs/running-rules)
and [actionlint release](https://github.com/rhysd/actionlint/releases/tag/v1.7.12).
Action revisions are resolved from official releases for [checkout](https://github.com/actions/checkout/releases/tag/v7.0.1),
[setup-java](https://github.com/actions/setup-java/releases/tag/v6.1.0) and
[upload-artifact](https://github.com/actions/upload-artifact/releases/tag/v7.0.2).
