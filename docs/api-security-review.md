# API and authentication security review (P8-T10)

Status: **DONE — approved auth and single-edge controls verified**
(2026-10-13). Baseline: `1e27eb8`, Java 21/Boot 4.1.0, phase branch
`feature/phase-8-production-engineering`. The user approved the auth request
marker and single-edge NGINX/rate-limit response proposals on 2026-10-13. This
authorizes those specific contract/edge changes, not new login/token flows,
financial/schema changes, Redis, T11 work or external deployment.
No external system or real credential is tested. `.env` and the pre-existing
untracked `.github/` remain user-owned. Do not commit an incomplete task.

## Findings and decision boundary

### SEC-T10-01 — Cookie-authenticated refresh/logout lack a CSRF barrier

Source evidence: `SecurityConfig.securityFilterChain` disables CSRF;
`AuthController.refresh/logout` accept the refresh cookie alone, with no request
body, Origin/Referer or custom-header check. `RefreshCookieFactory` issues a
host-only HttpOnly/Secure-production cookie with SameSite=Lax and auth-only path.
No CORS allowlist or cross-origin credentials configuration exists in source.

Exploit preconditions: a victim has an active refresh cookie and visits an
attacker-controlled **same-site, different-origin** HTTPS subdomain. A form POST
can send the target host's cookie to refresh/logout even though script cannot
read it or the response. Host-only cookies prevent reading/overwriting from the
sibling, not sending them to the target. Rotation/revocation is a session side
effect; this is not a claim of JWT theft or unauthorized financial mutation.
Ordinary unrelated-site POSTs are constrained by SameSite=Lax. MockMvc does not
simulate a browser's cookie rules: tests supply a cookie explicitly and model
the same-site precondition; real browser acceptance remains to be tested.

Approved and verified mitigation: require exactly one explicit
`X-FlowPay-CSRF: 1` header for login/refresh/logout (and register for a uniform
auth-client rule). The fixed value is a browser-request marker, not a secret or
a replacement authentication credential. Normal HTML forms cannot add this
header. Keep CORS closed by default and never enable wildcard/reflective
credentialed origins. If a separate-origin dashboard is needed, review an exact
operator allowlist plus Origin checks first; never derive trust from Host or
caller-controlled Forwarded headers. Reject missing/invalid headers with existing
`403 ACCESS_DENIED` Problem Details before credential verification, rotation or
logout, and do not set/clear cookies on rejection. Bearer financial and dashboard
APIs, token format, TTL, SameSite and financial Idempotency remain unchanged.
HTTP/non-browser auth clients must also add the marker: this is the explicit
user-approved auth request-contract change, documented in `api-contract.md`.

Regression tests require denial and unchanged refresh state. They were red on
the reviewed baseline; the implemented mitigation makes them pass without
disabling tests. The chain-local filter runs before bearer authentication and
body parsing; it is not globally registered twice and does no business/DB work.
Security's path-pattern matcher uses decoded MVC-compatible paths rather than a
raw URI prefix, preventing encoded-letter aliases from skipping the filter.
All auth CORS preflights are explicitly denied, even with a marker; other APIs
remain without a permissive CORS policy. No caller Origin/Host/Forwarded values
are used as an allowlist. Same-origin requests/non-browser clients with a marker
keep existing validation, credentials, rotation and logout behavior.

Verified internal reproduction (2026-10-13):
`AuthCookieSecurityReviewIntegrationTest` sends a same-site sibling Origin,
form Content-Type and an explicitly supplied valid cookie through the actual
security/controller/service wiring and owned PostgreSQL. Refresh returns **200**;
logout returns **204**, not the required **403**. Both regression cases fail.
The test profile changes cookie issuance Secure behavior, not the reviewed CSRF
chain; this is server-side evidence, not a deployed TLS/browser exploit test.
MockMvc request/response printing is disabled to avoid credential disclosure.
The existing `AuthSessionApiTest` passes all **11** cases. Combined focused run:
13 tests, two security-regression failures, zero errors/skips, 1m01s on Microsoft
Java 21.0.12/Maven 3.9.16. Full verify is intentionally deferred: the finding and
request-contract decisions must be resolved before a final security/wiring gate.

Basis: [OWASP CSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
describes custom-header/preflight protection and same-site subdomain concerns;
[Spring Security CSRF guidance](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html)
distinguishes stateless authentication from CSRF safety and recommends layered
cookie protections. JSON/CORS response denial alone is not a form-POST barrier.

### SEC-T10-02 — No bounded ingress auth/mutation abuse control

Source evidence: auth POST routes are public; login performs BCrypt, API-key
authentication reaches persistence, and no application limiter or selected
ingress edge exists in the repository. The monitoring lab intentionally has no
public business ingress. The API document lists `429 RATE_LIMIT_EXCEEDED`, but
does not define an implemented limiter, budget, scope or Retry-After behavior.
Do not treat a planned code in documentation as authorization to ship it.

Exploit preconditions: exposing the backend directly or through an unrestricted
edge allows high-rate credential guesses, password-hash/pool exhaustion and
mutation requests. This is source-level threat evidence, not a load result or
proof of per-account brute-force resistance. Shared NAT IPs and distributed
attackers limit the effectiveness of per-IP controls.

Approved and verified bounded policy for the staging baseline: a single pinned
NGINX edge with finite shared-memory zones, auth POSTs at 10 requests/minute/IP
with burst 5, financial/dashboard mutations at 10 requests/second/IP with burst
20; reject excess before forwarding, no automatic upstream retry of mutations.
Return `429 RATE_LIMIT_EXCEEDED` RFC 9457 Problem Details, requestId and a
documented conservative Retry-After (auth 60s, mutation 1s). These are declared
initial guardrails, not measured capacity/SLO or a multi-edge global quota.
Legitimate replays consume ingress budget but must not be re-keyed or invoke
the provider when denied. Tests must prove independent clients, burst recovery,
IPv4/IPv6 and no upstream/source side effect on rejection. Exact image/version/
digest and compatible configuration require review before implementation.

Trust boundary: baseline edge identifies the TCP peer (`$binary_remote_addr`),
not incoming X-Forwarded-For/Forwarded/X-Real-IP. Strip/overwrite forwarded
headers before proxying; app has no public bypass port. No broad real-IP trust
range or `set_real_ip_from 0.0.0.0/0`. A future upstream LB needs separately
approved exact trusted peers; until then spoofed headers must not alter bucket
identity. T13 must verify actual TLS ingress/network isolation, not just this
T10 candidate fixture. No Redis/new business module is proposed.

Basis: official [NGINX request limiting](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)
and [real-IP trust configuration](https://nginx.org/en/docs/http/ngx_http_realip_module.html).
The user approved this edge/runtime and concrete response/Retry-After proposal.
No production/staging deployment is authorized by this review.

Selected official image: `nginx:1.30.5-alpine` at
`sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94`;
actual binary reports 1.30.5. Official [stable release history](https://nginx.org/en/CHANGES-1.30)
dates it 2026-09-15, incorporating prior stable security fixes; the official
[image manifest source](https://github.com/docker-library/official-images/blob/master/library/nginx)
publishes the stable Alpine variant. This is source/release/config review, not
T11/T12 vulnerability/SBOM/image acceptance. No HTTP/2/3, QUIC, FastCGI, SCGI,
UWsgi, SSI, dynamic real-IP trust or scripting module is configured. Unused
compiled modules are not a claim that the image is vulnerability-free.

The reusable policy is `ops/ingress/nginx.conf`/`proxy.conf`, with finite zones,
two workers/512 connections, bounded HTTP waits, no access/raw-error logs and
mutation retries disabled. Read-only non-root UID 101 uses only finite `/tmp`
tmpfs for PID and all NGINX temp directories. Initial config-test caught the
default FastCGI temp directory on a read-only filesystem; all temp paths were
explicitly redirected, retaining non-root/read-only/cap-drop protections.
The server overwrites request IDs and Host, strips forwarded/rewrite headers,
and does not intercept upstream 429. Application prod forwarding strategy is
explicitly `none`; management still keys on the actual connector. T13 must
adapt only the reviewed listen include for real TLS and isolate ingress/backend.

The default NGINX 1MiB body limit and finite header/time limits apply at the edge;
they are not benchmark tuning or a general guarantee against resource exhaustion.
Review larger payload needs/413 semantics before staging. Rate limits are IP
guardrails, not a fix for credential stuffing from distributed clients or shared
NAT fairness. Owner: project maintainer; retune with T13/T14 measured workload.

## Reproduce the isolated policy gates

Run `sh ops/ingress/tests/smoke.sh` on Linux Docker (or Git Bash/Docker Desktop).
It creates only a uniquely named `flowpay-ingress-*` project. Real IPv4/IPv6 TCP
peers exercise concurrent auth/mutation budgets, shared routes/methods, refill,
safe 429/request IDs, upstream-429 passthrough, header spoofing, encoded auth
paths and denied backend DNS/direct IPv4/IPv6 access. The backend records only
fixed counters/booleans; no actual credentials or business source data is used.
All networks are internal, no host port is published, and no external system
is probed. Run one fixture at a time; subnet collision fails safely.

After full verification, set `FLOWPAY_JAR` to the final executable JAR and run
`sh ops/ingress/tests/application-smoke.sh`. It reuses pinned monitoring app/DB/
broker/egress definitions, but only its own disposable volumes and external
synthetic secrets. The real prod-profile application is behind the same policy,
without public backend/management ports. It checks approved auth, create/confirm/
refund and exact replays/conflicts, financial-replay flooding/429/refill and a
DB audit for single source/provider transaction and balanced unique Ledger.
This private HTTP policy fixture is **not** TLS staging, a browser cookie-engine
test, load/SLO or multi-edge global-quota acceptance. T13-T15 retain those gates.
EXIT traps irrecoverably remove only invocation-owned containers/networks/data
volumes. External synthetic secrets are retained for manual cleanup; no real
secrets, `.env` or dev/shared data is touched.

## Reviewed existing controls (not a blanket security certification)

- JWT: explicit RS256 decoder, issuer/timestamp validator; issuer generates
  15-minute tokens with user/merchant/role claims. Required dashboard claims are
  checked; API keys and JWTs use separate filter chains. No audience is currently
  issued; do not invent an audience/login flow under this task without review.
  Existing self-contained access-token/membership snapshots remain valid until
  expiry (900s default plus verifier clock tolerance), not immediate logout/user
  revocation. This is frozen MVP semantics, not a new bypass waiver; owner is the
  project maintainer. Require separate token/revocation contract review before
  production real-money use or a stronger immediate-revocation requirement.
- Refresh rotation: opaque hashed tokens, row-lock lookup, single-use rotation,
  revocation and expired/disabled/locked-user checks. Changing family-wide
  revocation or access-token immediate revocation is not in scope by inference.
- API keys: format and hash verification, revoked/expiry checks, active merchant
  required; public principals hold public IDs, not repository entities.
- Management: dedicated connector-local-port matching and public Actuator denial
  avoid trusting Host/Forwarded for security identity. T02 has an actual forwarded
  header regression. Staging network placement is still T13's gate.
- CORS: no explicit permissive policy; verify preflight and ordinary form POSTs
  separately. No cross-origin dashboard deployment is selected by the user.
- Errors/logging: centralized normalized Problem Details; unexpected errors do
  not log exception payloads/stack traces, SQL/bind and Apache wire logs are off.
  Request IDs are bounded correlation data, not authentication or rate-limit keys.
- Tenant authorization is enforced through authenticated public principals and
  module APIs; existing Payment/Refund/Webhook cross-merchant tests must run in
  the eventual focused/full gate. No unsupported claim of review completeness.

## Final verification (2026-10-13)

Final focused checkpoint after decoded-path hardening: **266 tests/21 classes**,
zero failures/errors/skips, **2m06s**, Microsoft Java 21.0.12/Maven 3.9.16. Includes
13 actual cookie/CORS/encoded-path cases and four marker filter units, positive
auth/rotation/registration/Phase 1 E2E, JWT/RSA/API-key validation, Payment/Refund/
Webhook tenant/API checks, actual prod management/header spoofing, errors and
ArchUnit/no-network-in-TX. Initial focused gate passed 32 tests; broader 263-test
gate also passed before the three additional encoded-path assertions.
Full clean verify passes **1,405 tests/195 suites**, zero failures/errors/skips,
**8m21s**, with real PostgreSQL/RabbitMQ, migrations, earlier financial/E2E,
actual production startup/management, tracing/metrics, architecture and
transaction boundaries. No required infrastructure suite is skipped.

Final edge policy run `flowpay-ingress-1791864939-748` exits 0: static topology/
policy validator plus **25** runtime assertions (**26** combined, summary excluded),
NGINX config-test and all IPv4/IPv6 limits/spoofing/encoded-path/refill/non-forwarding/
direct-backend checks pass. All owned containers/networks are removed. The Python
backend records only counters/booleans; this is policy proof, not actual app state.

Final JAR: `backend-0.0.1-SNAPSHOT.jar`, **79,498,273 bytes**, SHA-256
`240a95bc61895d095d3f0becff65daa078a2a367cf9ff806efdeafa2a3878401`.
Host build Microsoft Java 21.0.12/Maven 3.9.16/Boot 4.1.0; actual container runtime
is pinned Temurin 21.0.10+7 JRE (unchanged monitoring/egress baseline). Native gates
use Linux/amd64 Docker Engine 29.6.2/Compose 5.3.1, four CPUs/3,920,220,160 bytes
available with finite per-service budgets.

Real-app run `flowpay-ingress-app-1791865145-1220` exits 0 on that exact JAR:
approved auth marker/CORS denial, legit registration/login/key, Create/Confirm/
Refund and exact replays/conflict work through the edge. Concurrent confirmation
replays include safe 429 despite changing spoofed identity headers; accepted
replays and a same-key replay after refill preserve their stored response.
Management is not exposed. Final owned SQL audit verifies exactly one Payment,
one provider PaymentTransaction, one successful Refund, two unique Ledger
transactions/four entries, 1,000 refunded/reserved-zero and zero imbalance.
Static config/topology gate, eleven explicit runtime assertions and the financial
audit pass. An initial fixture run was rejected after discovering that Compose
auto-started a second probe; probes now use a checks-only profile. No Java/config/
JAR change was needed and no rejected run is counted as acceptance.
Owned containers/networks/data volumes are removed. External synthetic secrets
remain for manual cleanup: `C:/Users/lptdn/AppData/Local/Temp/flowpay-ingress.Vxgy4s`
(final) and `C:/Users/lptdn/AppData/Local/Temp/flowpay-ingress.bP35w7` (rejected).
No dev/shared resources or real credentials were touched. Cleanup explicitly
includes the checks profile. An ownership audit found the stopped auto-started
probe from the rejected run; the revised profile-aware cleanup removed that
exact owned container after checking its project label and stopped state.
The final zero-resource audit is performed after this correction.

Affected monitoring run `flowpay-monitoring-1791865252-1494` exits 0 on that exact
artifact, using the explicitly updated auth client marker. Native config/rules/
Collector, prod readiness, dashboards/PromQL, real metrics/stored traces and
all-monitoring outage/recovery pass. Final SQL still verifies three partially
refunded payments/three successful refunds/six unique Ledger transactions/twelve
balanced entries. All owned containers/networks/data volumes are removed;
external synthetic secret directory is retained for manual cleanup at
`C:/Users/lptdn/AppData/Local/Temp/flowpay-monitoring.SY9gQU`.
Native Webhook egress run `flowpay-egress-1791865498-1677` exits 0 on the same
final JAR/matching full-gate test classes and pinned Temurin 21.0.10+7 runtime.
All **34** independent DNS/TLS/IP-SAN/HMAC/deadline/proxy/bypass assertions pass,
including public IPv6 literal and negative hostname/chain/IP-SAN. No signed
private/mixed/redirect request or invalid HMAC is observed. Owned containers,
networks and disposable synthetic TLS volume are removed. Read-only post-run
ownership-label checks confirm zero containers/networks/volumes for all eight
T10 diagnostic/prototype/final fixture invocations. No dev/shared data was
deleted. Image cache and the three external synthetic secret directories remain.

The two explicitly approved findings have verified mitigations. All focused/full/
native gates above pass on the recorded artifact; static diff/shell syntax and
native topology/config validation also pass. No Java dependency, schema, token
format/TTL/cookie, business financial state/locking/event/TX or provider retry
semantics were changed. Phase 8 remains IN PROGRESS, not production ready.

Bounded limitations retain named owners: project maintainer owns same-origin
UI/any future CORS allowlist, per-IP/shared-NAT/distributed-abuse policies and
frozen token snapshot semantics; T11/T12 own scans/SBOM/image acceptance, T13
owns TLS staging and actual host/backend isolation, and T14/T15 own capacity/
multi-edge workload evaluation. No known finding is silently accepted as fixed,
no live external exploitation is performed, and no new login/role flow is added.
Commit only verified T10 tracked files on the phase branch; local phase/index/
agent documents, `.env` and `.github/` are excluded. No push/merge or T11 work.
