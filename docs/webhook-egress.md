# Webhook egress protection and transport review (P8-T09)

Status on 2026-10-11: **DONE — isolated enforcement and approved Java 21 transport verified**.
Final clean verify, the expanded final-artifact network gate and affected
monitoring acceptance pass on the same JAR. This is not T13 staging or release
image acceptance; no external deployment is claimed.
The user approved Apache HttpClient 5 after the JDK Java 21/25 failures.
No TLS verification bypass or URL-contract restriction is authorized.

## Design and trust boundary

Merchant URLs/DNS/redirects are untrusted. Registration-time string/literal
validation does not bind a later network connection to an approved address.
The implementation uses a dedicated Apache async HTTP client, an operator-configured fixed CONNECT proxy,
and an application container attached only to a private internal network.
DB/Rabbit/telemetry remain separately reachable there; only the proxy joins an
outbound network. No JVM default proxy/nonProxyHosts/direct fallback may select
the merchant connection path. Production startup requires
`WEBHOOK_EGRESS_PROXY_HOST` and `WEBHOOK_EGRESS_PROXY_PORT`. Explicit Apache TLS
enforces trusted chains and both JSSE HTTPS and default hostname/IP-SAN
verification. The JDK HttpClient disable flag cannot affect it; the fixture sets
that flag while all negative TLS cases must still fail.

Resolve **only the trusted proxy hostname** at client initialization with a
connect-budget timeout; keep an immutable address snapshot. Its resolver refuses
every other hostname. Apache otherwise resolves its connection peer synchronously,
which could escape a delivery deadline before `execute()` returns. No merchant
DNS lookup or approval is moved into the application. Refresh the snapshot by
restarting the client/application after proxy DNS/address changes. Slow/failed
startup DNS fails closed with fixed diagnostics. A JVM lookup ignoring interrupt
may finish on its daemon thread but cannot initiate a merchant request.

Use HTTP/1, 32 total/eight per-route pool slots and two I/O threads: finite bounds,
not measured capacity. TCP/TLS retain the connect budget; pool/CONNECT/TLS/header
waits and signing share the monotonic request deadline. A separate headers future
leaves the exchange pending for hard cancellation of unread bodies. Prefetched
bytes are discarded, never buffered or persisted. Plaintext CONNECT refusals
lack a TLS session and become `TRANSPORT_FAILURE`, never merchant HTTP ACKs.
Redirects, automatic retries, auth, cookies and compression are disabled. No
wire/body/header logs or outgoing trace/baggage headers are enabled.
Core 5.4.4's [HTTP/1 parser defaults](https://raw.githubusercontent.com/apache/httpcomponents-core/rel/v5.4.4/httpcore5/src/main/java/org/apache/hc/core5/http/config/Http1Config.java)
are finite: 100 headers, 8,192-byte lines and ten empty lines. This source review
is not a capacity benchmark or a claim of oversized-header fixture coverage.

The candidate is Canonical's image
`ubuntu/squid:6.6-24.04_beta@sha256:6a097f68bae708cedbabd6188d68c7e2e7a38cedd05a176e1cc0ba29e3bbe029`.
Actual inspected binary: Squid **6.13**, Ubuntu package
`6.13-0ubuntu0.24.04.3`; the channel tag is not the binary version.
This is the selected T09 enforcing proxy, not a scanned/accepted release image.

`ops/egress/squid.conf` denies non-CONNECT and special-use destinations before
allowing a tunnel; no peers, TLS interception, hostname rewrite or access/body
logs. Its `dst` ACL evaluates the cached address set, not just one preferred IP.
Connections use that proxy's destination cache rather than a separate application
lookup followed by hostname re-resolution. The network fixture exercises delayed
family answers/concurrent requests and a new tunnel after controlled rebinding.
See upstream [destination ACL](https://github.com/squid-cache/squid/blob/SQUID_6_13/src/acl/DestinationIp.cc),
[IP cache](https://github.com/squid-cache/squid/blob/SQUID_6_13/src/ipcache.cc) and
[CONNECT implementation](https://github.com/squid-cache/squid/blob/SQUID_6_13/src/tunnel.cc).

The conservative policy excludes IPv4 special-use/private/shared/metadata,
documentation, benchmarking, multicast/reserved prefixes. IPv6 is limited to
current global unicast 2000::/3 minus special-use 2001::/23, 2001:db8::/32,
2002::/16 and 3fff::/20. This deliberately also denies special-use global
exceptions inside those blocks. Review current IANA registries before changing
the policy; update the Java literal policy, proxy ACL and acceptance fixtures
together. No DNS resolution is added to domain/registration validation.

Squid internally represents IPv4 in ::ffff:0:0/96. A naive `::/3` deny overlaps
IPv4 ACLs and produces an ignored-range warning. The fixture rejects any parse
warning both at proxy startup and fixture acceptance; two explicit IPv6 intervals
exclude that mapped slice, while IPv4
rules continue to handle mapped addresses. Proxy runs non-root, read-only,
without capabilities, with finite memory/CPU and tmpfs. Trusted resolver and
network isolation are mandatory, not properties of a hostname setting alone.
No production/staging host firewall or external-provider deployment is claimed.

## Reproduce the required gate

Build matching JAR/test classes using repository Maven verification first.
Run on a Linux Docker engine with IPv6 support and POSIX shell (Git Bash on
Windows is supported):

```sh
export FLOWPAY_JAR="$(pwd)/target/backend-0.0.1-SNAPSHOT.jar"
export FLOWPAY_TEST_CLASSES="$(pwd)/target/test-classes"
sh ops/egress/tests/smoke.sh
```

Only invocation-owned `flowpay-egress-<timestamp>-<pid>` resources are created.
There are no host ports, external DNS queries, Internet merchant calls or probes
of real private services. Globally classified fixture IPs exist **only** on an
internal Docker network: 11.203.0.0/24 and 2606:4700:ffff::/64. The backend uses
172.30.231.0/24 and fd00:231::/64. Run one fixture at a time; subnet collisions
fail rather than modifying other networks. Synthetic TLS keys/CA/truststore
are generated in an invocation-owned volume, never repository files. The EXIT
trap irrecoverably removes that fixture volume, containers and networks only.

The probe loads the actual JAR dependencies and dedicated production-profile
HTTP/URL configuration plus the real adapter/signer. It is not a full application
deployment. Production localhost exceptions remain off; the client trusts only the
generated fixture certificates through its test JVM truststore. HTTPS hostname
and IP-SAN verification remain enabled. Python independently verifies raw UTF-8
bytes, HMAC, headers and private-sink request counters. No signature/body/secret
values are printed. A separate test control-plane read models permitted private
service connectivity, not a merchant bypass. Full Java verification covers real
PostgreSQL/Rabbit/financial boundaries separately.

The required IPv6-literal assertion is retained. The final Apache transport passes
the complete expanded IP-SAN gate on Java 21 without weakening TLS. The exact
artifact/runtime and separate Java/network/monitoring results are recorded below;
the monitoring definition uses the same pinned Java 21 JRE as the network probe.

## Historical JDK finding and approved replacement

Public IPv4 hostname/literal and IPv6 hostname TLS/HMAC connections work through
the candidate. An already accepted public **IPv6 literal URL** fails in the
proxied JDK transport: `AbstractAsyncSSLConnection.createSSLParameters` attempts
`SNIHostName` construction and throws `IllegalArgumentException` (non-LDH ASCII).
This was reproduced with Temurin 21.0.10+7, 21.0.12.1+1 and the user-authorized
Temurin **25.0.4.1+1 LTS** compatibility spike. Neither tested runtime upgrade
resolved it. The fixture's raw transport diagnostic traverses the exception
cause chain and confirms `SNIHostName` construction, printing only a fixed
classification, not exception messages, headers, signatures or bodies.
The rejected JDK draft normalized transport argument exceptions to
the existing `TRANSPORT_FAILURE`, with no raw exception text in public history.
That prevents leakage but **does not** satisfy successful IPv6-literal delivery.

The rejected newer-runtime candidate was used **only** by the diagnostic fixture:
`eclipse-temurin:25.0.4.1_1-jre-jammy@sha256:25777acfabf927084b7ef46d8bc786b6203c8c344f56541054238b8c4fe73db9`.
Earlier Java 21.0.12.1 fixture image:
`eclipse-temurin:21.0.12.1_1-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b`.
The monitoring/application runtime remains Java 21; no upgrade was adopted.
IPv6 hostname success
does not waive the literal requirement or justify narrowing the API contract.
Upstream [JDK TLS connection code](https://github.com/openjdk/jdk21u/blob/master/src/java.net.http/share/classes/jdk/internal/net/http/AbstractAsyncSSLConnection.java)
has evolved; source inspection alone is not proof that a particular binary
contains a fix. [RFC 6066 section 3](https://www.rfc-editor.org/rfc/rfc6066.html#section-3)
distinguishes DNS SNI names from IP literals; disabling certificate/hostname
verification is not a remedy.

The authorized runtime spike is complete and rejected. The user then approved
an **Apache HttpClient 5 transport spike**, scoped to the Webhook infrastructure
adapter, with adoption only after complete verification. An API
restriction on public IP literals is a separate explicit contract decision, not
an implicit fix. The task specification requires this review when the existing
JDK/proxy path cannot pass; no dependency/contract/runtime upgrade is silently
adopted here.

## Approved transport review and dependency selection

Retain Java 21, `WebhookHttpClientPort`, the existing enforcing proxy/network
boundary, URL policy and all business/API/event/transaction contracts. Replace
only the dedicated Webhook transport under explicit dependency approval.
Apache's [default TLS strategy](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/ssl/DefaultClientTlsStrategy.html)
and [hostname verifier source](https://github.com/apache/httpcomponents-client/blob/rel/v5.6.1/httpclient5/src/main/java/org/apache/hc/client5/http/ssl/DefaultHostnameVerifier.java)
provide source-level review evidence for IP-SAN verification. Acceptance relies
on the actual proxy/IPv6/TLS tests below, not source inspection alone.

The approval retains these implementation and verification constraints:

- Dedicated asynchronous client behind the current port; fixed CONNECT route,
  no system proxy/nonProxyHosts fallback, no direct merchant DNS path. Squid still
  owns all-answer destination enforcement; Apache is not an SSRF policy by itself.
- Default trusted certificate chain plus HTTPS hostname/IP-SAN verification;
  normal DNS SNI and no IP-literal SNI. No trust-all or no-op verifier.
- Preserve exact outbound UTF-8/HMAC bytes and four existing headers. Disable
  redirects, automatic retries, cookies, compression and credential/auth behavior
  not in the existing delivery contract. No trace/baggage headers or wire logs.
- Bound connection/pool/TLS waits and retain the configured five-second overall
  deadline, with cancellation/release on timeout or interruption. Complete at
  response headers and cancel unread bodies, not after buffering/draining them.
  Apache explicitly documents that [response timeout is not a deadline](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/config/RequestConfig.Builder.html#setResponseTimeout(org.apache.hc.core5.util.Timeout));
  merely setting it is insufficient to preserve the current contract.
- Keep no-TX execution, normalized failure codes, safe spans and graceful client
  closure. Verify unrelated progress and resource release under slow/bad peers.
- Inspect the resolved dependency tree and current Apache release/security
  notices before selecting/pinning the Client/Core pair. The installed Boot
  4.1.0 BOM manages Client 5.6.1/Core 5.4.2; that is not approval to adopt those
  versions blindly. Apache's [release history](https://hc.apache.org/news.html)
  records an async SSL-parameter fix in Client 5.6.4 (2026-08-10). A page entry
  dated 2026-10-12 is future-dated relative to this review and is not used as
  evidence of a release available on 2026-10-11. Any BOM override needs an
  explicit rationale and dependency/security compatibility verification.
- Rerun focused tests, full clean verify, the complete unchanged IPv6/DNS/TLS/
  HMAC/bypass gate on the final artifact and affected monitoring smoke. Only then
  accept/pin the transport/proxy, synchronize tracked design and commit T09.

Selected Client **5.6.4** and Core/HTTP2 **5.4.4**, overriding Boot 4.1.0's
5.6.1/5.4.2 properties. Client includes the async SSL-parameter application fix;
Core includes the stable async producer/reset fixes. Maven resolves exactly one
Client/Core pair. No optional compression/auth/observability dependency is added
or enabled. Auth, compression and wire logging are disabled; source/release
review and negative TLS tests do not replace T11's repository-wide scanner gate.
Future-dated 5.6.5 and alpha/beta branches were not selected. Java 21 is unchanged.
T09 is verified and eligible for its task-scoped commit. No push/merge or T10
implementation is authorized as part of this continuation.

## Historical spike evidence (not final Apache acceptance)

- Focused Java verification: 127 tests/five classes, zero failures/errors/skips
  (39.649s including package); Java 21.0.12/Maven 3.9.16.
  Rerun after the test-only diagnostic addition: the same 127 tests pass in
  31.982s; final cause-chain probe compilation passes in 21.893s.
- Initial isolated Python TLS/HMAC spike: valid-public success, private-only
  denial, two concurrent public-A/private-AAAA CONNECTs both 403 and zero signed
  mixed-target requests. This is candidate evidence, not final Java acceptance.
- Full `clean verify`: 1,378 tests/191 suites, zero failures/errors/skips, 7m16s;
  real PostgreSQL/Rabbit, production management, financial, migration,
  observability, architecture and transaction-boundary regressions included.
- Final JDK network run on that full-gate artifact: 29 adapter/network diagnostic
  assertions plus one proxy warning/startup assertion passed; required public
  IPv6 literal compatibility assertion failed. Exit **1**, not acceptance.
  Private/mixed/redirect sink counters and invalid-HMAC counters stayed zero;
  direct IPv4/IPv6 sockets were denied. Owned fixture containers/networks/TLS
  volume removed and absence checked. No dev/shared resource was removed.
- JAR: `backend-0.0.1-SNAPSHOT.jar`, 77,212,049 bytes, SHA-256
  `cae8faa6a0b3dd2ea13c9b3ae36da33a4c576143e4732cdb83112b62634a8936`.
  Host build Microsoft Java 21.0.12/Maven 3.9.16; network probe Temurin
  21.0.12.1+1; Linux/amd64 Docker Engine 29.6.2/Compose 5.3.1,
  four CPUs/3,920,216,064 bytes available, finite per-service budgets in Compose.
  Synthetic certificates, not public merchant infrastructure or TLS staging.
- User-authorized Temurin 25.0.4.1+1 LTS spike on the **same unchanged JAR**:
  final diagnostic run exits 1 at the required IPv6-literal assertion. All 30
  independent diagnostic/startup assertions still pass; raw transport diagnostic
  confirms JDK IPv6 SNI construction failure. No production Java code/dependency
  changed during this continuation. Full Java verification was not repeated:
  the existing 1,378-test Java 21 gate remains artifact provenance, not Java 25
  acceptance. A rejected fixture-only runtime does not justify claiming a new
  application full gate. Monitoring smoke was not rerun while T09 remained red.
  Invocation `flowpay-egress-1791714030-54` and earlier owned spike resources
  were removed, including disposable TLS volumes; image cache remains.
- At the rejected-spike checkpoint, no task commit/push/merge was made. `.env`,
  `.github/` and local-only agent/planning documents were not staged. T09 remained
  incomplete; no T10 work occurred.

## Final Apache verification (2026-10-11)

- Focused: **205 tests/nine classes**, zero failures/errors/skips, **44.165s**.
  Includes adapter/real HTTP, proxy DNS, header cancellation, URL/prod guards,
  architecture and transaction boundaries. An initial full run found a test-only
  reactor-start scheduling race (`INACTIVE` immediately after asynchronous
  `start()`). A bounded two-second Awaitility assertion fixes the test without
  changing production startup or weakening the required `ACTIVE` assertion.
- Full **clean verify: 1,388 tests/193 suites**, zero failures/errors/skips,
  **8m37s**; Microsoft Java 21.0.12/Maven 3.9.16, Boot 4.1.0. Actual PostgreSQL
  and RabbitMQ suites, production startup/management, financial/E2E, tracing,
  migrations, architecture and no-network-in-TX regressions run without exclusion.
- Final JAR: `backend-0.0.1-SNAPSHOT.jar`, **79,496,386 bytes**, SHA-256
  `3bab47c2f277023151cf0db951391fb4a0dcc3e6353c32bc116c8ca25db76e67`.
  Resolved Client 5.6.4/Core 5.4.4/HTTP2 Core 5.4.4; no duplicate client pair.
- Owned network run `flowpay-egress-1791716785-1605`: **exit 0**, all **34**
  independent network/transport/startup assertions pass (summary lines excluded).
  Probe runtime Temurin **21.0.10+7-LTS**, identical pinned JRE to monitoring.
  Public IPv4/IPv6 hostnames and literals succeed with real trusted TLS/HMAC;
  wrong hostname, untrusted chain and trusted DNS-only certificates lacking
  IPv4/IPv6 IP-SAN fail. Private/mixed/reserved answers, delayed concurrent A/AAAA
  cache use, rebinding, redirect, proxy refusal and direct IPv4/IPv6 bypass are
  denied. Five-second deadline and unrelated progress pass. No blocked/redirect
  signed requests, invalid HMAC or unexpected merchant requests are observed.
  Unsafe ACL warnings refuse proxy startup. All owned fixture containers,
  networks and disposable TLS volume are removed; no dev/shared resource touched.
- Affected monitoring run `flowpay-monitoring-1791716846-865`: **exit 0** on the
  exact same JAR and Java 21 JRE. Native configuration/Prometheus rule/Collector
  validation, prod readiness with required proxy settings, provisioned Grafana
  queries and stored E2E traces pass. Stopping all four monitoring services keeps
  financial/replay/conflict checks working; recovery ingests fresh metrics/traces.
  Final SQL proves three partially refunded payments, three successful refunds,
  six unique Ledger transactions/twelve entries and zero imbalance. This is a
  telemetry/financial wiring regression, not a substitute for the separate TLS
  network fixture. All owned containers/networks/data volumes are removed;
  post-run ownership-label checks find zero resources for both projects. No
  dev/shared resources were deleted. External **synthetic** secret files remain
  for manual cleanup at `C:/Users/lptdn/AppData/Local/Temp/flowpay-monitoring.I9FYrQ`;
  no secret values are logged, documented or committed.

These are isolated Linux/amd64 Docker fixtures, not Internet merchants, provider
deployment, vulnerability/SBOM/image acceptance, measured throughput or staged
host-firewall proof. Docker Engine 29.6.2/Compose 5.3.1, four CPUs and
3,920,220,160 bytes available; all fixture services have finite Compose budgets.
T11-T13 retain supply-chain/image/staging gates; T14-T16 retain performance and
failure/recovery measurement. Do not start T10 as part of this task.
