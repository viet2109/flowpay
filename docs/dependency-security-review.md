# P8-T18 dependency and runtime security remediation

## Authority and constraints

2026-10-17: the owner approved reviewing and fixing existing dependencies and
the application base image after the rejected exit audit. This does not approve
scanner exceptions, suppression, rewriting credential/example fixtures to hide
detection, API/schema/event/state changes,
deployment or publication. `ops/ci/exceptions.json` remains empty. Source secret
findings remain blocking under the owner's earlier explicit decision.

2026-10-18 continuation: the owner authorized the
[individual source-finding review](secret-findings-review.md). All10 report rows
are from `generic-api-key`, at seven test/example/public-prefix locations. The
initial review found no justified code/fixture correction and did not infer
permission to accept false positives. The owner then explicitly approved the
bounded source-review mechanism in that document, without real-secret waivers,
fixture changes, detector suppression or image exceptions. Its verification is
separate from the earlier failed source scans below. Phase8 is not frozen and
no real secret material is requested.
The new full security invocation PASSes with10 total/10 accepted/zero unreviewed
source findings, zero SAST/advisory matches,235 Maven components and all native
controls passed.203 offline controls pass on Windows/Linux. The application
artifact is unchanged; actual release preflight now rejects dirty/uncommitted
source and final artifact-bound operational gates remain open.

## Reviewed candidate

Maven Central metadata and published POMs were checked directly. Use the stable
[Spring Boot 4.1.1 maintenance release](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/),
not a 4.2 milestone or a paid patched distribution. Its BOM supplies Framework
7.0.9 and PostgreSQL JDBC 42.7.13, covering the reported Spring MVC and
[JDBC channel-binding fixes](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-j92g-9f8w-j867).
Java 21 remains the build/runtime target. No new direct library is introduced.

Boot 4.1.1 alone is insufficient for the observed findings. The POM documents
these scoped, family-wide BOM/property overrides:

| Component | Previous effective version | Boot 4.1.1 | Selected | Review |
|---|---|---|---|---|
| Tomcat | 11.0.22 | 11.0.24 | 11.0.27 | Same 11.0 line; [security fixes](https://tomcat.apache.org/security-11.html) and [changelog](https://tomcat.apache.org/tomcat-11.0-doc/changelog.html); validate executable nested-JAR startup and HTTP/security regressions |
| Jackson 3 family | 3.1.4 | 3.1.5 | 3.1.7 | Same 3.1 patch line; [release fixes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7) for bounded parser errors, numeric parsing and forward references; validate stored JSON, events and signed public payloads |
| Jackson 2 family | BOM 2.21.4 | 2.21.5 | 2.21.7 | Keep Boot's compatibility family aligned; [release fixes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21.7); do not substitute Jackson 3 artifacts into Jackson 2 packages |
| Netty | 4.2.15.Final | 4.2.17.Final | 4.2.19.Final | Same 4.2 line; [maintenance/security release](https://github.com/netty/netty/releases/tag/netty-4.2.19.Final), including subsequent SNI/parser fixes; no reactive server migration |
| Rabbit Java client | 5.30.0 | 5.30.0 | 5.37.0 | Existing 5.x library; [frame-size hardening](https://github.com/rabbitmq/rabbitmq-java-client/releases/tag/v5.34.0) plus [current maintenance release](https://github.com/rabbitmq/rabbitmq-java-client/releases/tag/v5.37.0); preferred TLS 1.3 change warrants real broker/reconnect/confirm tests |

The approved Webhook Apache Client stays 5.6.4, now inherited from Boot rather
than redundantly overridden. Core stays explicitly 5.4.4 (Boot manages 5.4.3),
preserving the earlier [transport review](webhook-egress.md). No HTTP client,
redirect, DNS, signing, timeout, retry or egress policy is changed.

The official Temurin Java21.0.12.1+1/Jammy image tag was rebuilt upstream.
Use the reviewed **linux/amd64** manifest
`sha256:8ecbec1f32912979c15b86aaf98f7082a77d318a7f92af3d9fe2c0fd2723df10`
instead of `sha256:8c2dddf1bb2a8455160f4e23080059de5003eddc5cb839130b177c6be0c2cfe0`.
Registry annotations identify creation `2026-10-17T00:19:25Z`, source
[adoptium/containers revision 511f935](https://github.com/adoptium/containers/tree/511f9356dc4d50932a0a5f8cfb0f87ed1aef4f07/21/jre/ubuntu/jammy),
and Ubuntu22.04 base digest
`sha256:98510ac07bb4545e389be502a8918b385c018bfe4ceeb5ac7e0375c782268b04`.
Keep digest pinning, Java21, glibc/Jammy, UID/GID, budgets, artifact-first build
and read-only/no-network controls unchanged. Update the same runtime gate's
certificate-helper image too. Final native ingress/monitoring/egress labs also
use that same reviewed Java21 base for their existing Java and certificate
helpers, rather than silently testing the new JAR on historical Java21.0.10.
Only those existing image references change; their networks, caps, scenarios,
timeouts, proxy/TLS checks and application settings are unchanged. A focused
control prevents those lab references drifting from the reviewed Dockerfile.
A newer digest is a candidate, not a clean scan.
Read-only inspection of the pulled amd64 image reports `libssl3` and `openssl`
at `3.0.2-0ubuntu1.30` (previous image libssl3 `3.0.2-0ubuntu1.29`). The new image
still requires a complete fresh OS/Java scan; package-version inspection alone
does not clear CVE-2026-84782 or establish that unrelated OS packages are safe.

## Verification status

Resolved Maven coordinates match the selected versions; the approved Apache
Client/Core pair remains5.6.4/5.4.4. All183 offline controls pass on Windows and
pinned Linux Python3.13.12. The first tracked-only Linux snapshot missed the two
new task documents required by link validation; adding only those explicit files
produced the complete pass, without adopting `.env` or local instructions.

The load/failure unit controls now use disposable synthetic matching config
fixtures, with changed-byte/hash rejection tests, instead of demanding that a
developing checkout still equal the historical T13 Dockerfile. Real CLI gates
and historical manifests/approvals remain unchanged and reject mismatched inputs.
This changes neither workload acceptance nor the permitted host envelope.

The new `clean verify` passes **1,407 tests /196 required suites, zero failures,
errors or skips**, with real PostgreSQL/RabbitMQ, JDK21.0.12 and actionlint.
Build/test inputs are
`8d0e1567010aef79ad156cbb953fc542219d49c361877cff016c5257c10d0e11`;
the exact verified JAR is
`3212f15b52fb36ef9c967cb05e49b8bf897b822c0fa19b7424225f1c657be0d7`.
No unchanged full suite was repeated during this continuation.

Exact-JAR image build and non-root/read-only/missing-prod-configuration controls
pass. Local Linux/amd64 image identity is
`sha256:951f5e3b2cc9124eb35af64c9969a8a3999884a9b05484e9c79e533ab6bbb993`,
170,861,994 bytes, runtime Java21.0.12.1. It has not been pushed to a registry.
Fresh full source/Maven and actual-image scans completed with the unchanged
Grype0.120.1 and validated DBv6.1.10 built2026-10-17T06:27:09Z:

| Security scope | Pre-remediation | New observed result |
|---|---|---|
| Maven graph, including test/provided | 234 components,17 blocking HIGH/CRITICAL matches | 235 components,**zero advisory matches** |
| Main-Java SAST | Zero findings | Zero findings; required coverage and clean/adversarial controls pass |
| Tracked-source secret-format scan | 10 findings | **10 findings, gate FAIL**, no automatic waiver |
| Actual application image | 299 components,18 blocking HIGH/CRITICAL matches | 301 components,**zero HIGH/CRITICAL/UNKNOWN**, image gate PASS;94 MEDIUM/41 LOW/9 NEGLIGIBLE retained |
| Image application resources | Zero secret findings | Zero findings; not proof about every vendor binary or Git history |

Native vulnerable-inventory and unavailable-DB controls pass in both scans.
No finding, threshold or detector was suppressed. These are advisory matches,
not proof of reachability, exploitability or absence of unknown vulnerabilities.
The image's remaining non-blocking OS findings still need periodic maintainer
review; a successful application-image gate does not scan all infrastructure.
Native production-image runtime PASS on this exact image: active-worker
SIGTERM, broker-outage source commit/restart recovery, duplicate-safe Ledger,
Webhook replay/HMAC, all13 migrations and persistent external keys. Owned
runtime resources were removed and absence checked. The first canonical staging
attempt passed the original Phase7 checksum/financial/ciphertext upgrade checks,
then failed `owned monitoring: grafana failed` and completed owned cleanup.
That condition means the Grafana health/database check did not succeed within
its existing120s wait, not a financial assertion failure. The original rejected
report is retained as `staging-grafana-rejected.json`; fresh staging was not
reached in that invocation. A separate read-only diagnostic repetition of the
Phase7 case passed in full, without resource/deadline/assertion changes.
During the repetition an owned-container state inspection showed Grafana
running, OOMKilled=false and memory limit201,326,592 bytes. Its Grafana/metrics/
trace checks subsequently passed; this does not establish the first failure's
cause. No OOM/resource-contention explanation is claimed without evidence.
A new **unmodified canonical invocation PASSes both Phase7 and fresh cases**:
legacy checksum/finance/ciphertext preservation, topology before producers,
two-replica metrics/dashboard/stored trace, private IPv4/IPv6/TLS/proxy-bypass
boundaries, SUCCESS/DECLINED/UNKNOWN and duplicate-safe consumers, Webhook
HMAC/external-key continuity, rejected-key configuration rollback and restart.
Both cases complete owned cleanup; Docker has no running containers afterward.
The diagnostic-only pass is not substituted for this canonical gate. The first
Grafana rejection remains visible and unexplained; later passes do not prove
that an intermittent operational issue has been fixed or waive stability review.
Reports record a dirty worktree at source HEAD066ceca: this is not a clean
committed release attestation. Do not transfer earlier evidence to these changed
inputs. The
pre-remediation JAR and all safe reports were preserved outside `target` before
any clean build. Historical load/failure/restore approvals remain bound to their
old artifact and must not be rewritten to claim acceptance of this candidate.

The [exit audit](production-exit-audit.md) remains BLOCKED until every required
gate is actually accepted; removing dependency matches alone cannot clear the
remaining source secret findings or operational acceptance requirements.

Unmodified operational entry points were exercised again and reject this
candidate **before creating workload projects**: load acceptance requires the
frozen T13 artifact, failure/restore require the unchanged T13 image/JAR, and
runbook rehearsal requires the same historical image. The actual release
preflight also FAILS because source security is not passed. No long load/soak,
two-replica fault drill, restore or native runbook acceptance is newly claimed.
Historical manifests, approvals, resource budgets and security policy are intact.

Safe evidence fingerprints at this checkpoint:

| Report | SHA-256 |
|---|---|
| `verification.json` | `764ea46f2c7c2df2e066570e28e272fedb5d33a9b700f8ee657afd1ae046526e` |
| `image-build.json` | `fe7f19f5da9fe38de77fe38879aad9501102f388881eb837050fb0d0750436d2` |
| `security.json` (FAIL) | `c53d84c5fb3523900ae01de3bbcbbb815c20fc87105605d7f1e3b07ea18bed2b` |
| `image-scan.json` (PASS) | `2d2d185efeaccdb615e988f3171163ec3f78d8c8133a04ddac175dc8f614400f` |
| `image-runtime.json` (PASS) | `a2584e125cf92f2e25873324ea56210eb1abd7476790abef5719af90480078d3` |
| `staging-grafana-rejected.json` (FAIL) | `4f57fe584042155929a8f7e921700917965ef32bae7b4903846a035eae8d84f3` |
| `staging-grafana-diagnostic.json` (diagnostic-only Phase7 PASS) | `94d6d0d57ad42132af2fea0602c4da19a90f2f628b635ffee79fca275344bce4` |
| `staging.json` (canonical Phase7 + fresh PASS) | `45a5f7699a11f334caa0ce6d8d9a87f12048fa59ea92ffbb3cadcc7fde7ec176` |

The full security snapshot includes842 tracked/intent-to-add task files,
excluding `.env`, local instructions and phase/index documents. It predates
the final result-wording updates; do not represent it as a clean committed
attestation for those later documentation edits. Generated safe reports/SBOMs
are ignored artifacts, not staged source files.

Final result-wording edits are followed by a separate tracked-source secrets /
main-Java SAST rescan (`remediation-source.json`), not a replacement Maven or
image scan and not a committed-release attestation. Local doc/runbook/link and
183-control checks remain separate from the full Java and native gates.

The current branch remains `feature/phase-8-production-engineering`, HEAD
`066cecafd18769f006973cc87cc7579a164a7c73`. A fresh fetch confirms0 behind/17
ahead of `origin/main`; protected outgoing paths and staged contents are empty.
At that checkpoint all task changes remained uncommitted. `.env` is user-owned, unread and untracked;
phase/index corrections stay ignored and local. No push, merge or public/retained
deployment occurred. That checkpoint was not eligible for task-DONE/freeze.
The [exit audit](production-exit-audit.md) records subsequent bounded-source
approval and new artifact-bound operational repetitions; none of the preceding
historical report hashes is rewritten to claim those later passes.
