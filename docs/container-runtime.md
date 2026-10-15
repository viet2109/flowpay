# Verified production runtime image

## Scope and release boundary (P8-T12)

Linux/amd64, Java 21, simulator providers, no registry or cloud deployment.
This task packages the application; T13 owns the actual staging/TLS/network and
upgrade deployment. Successful runtime tests do not override blocked T11/T12
security gates. The owner chose **no secret exceptions**, and `exceptions.json`
remains empty. Do not upgrade application dependencies or suppress findings just
to make packaging green. No public endpoint or frontend deployment is created.

## Artifact-first build decision

The reviewed equivalent to a multi-stage Maven build is an **artifact-first**
runtime build: compile/test exactly once with JDK21 `clean verify`, then package
that exact executable JAR. A second Docker Maven compilation could produce a
different, untested artifact. The JRE-only final image needs no compiler or Maven.
This is procedure/artifact reproducibility, not a promise that independent Maven
builds are byte-identical forever.

`ops/ci/gate.py verify` records passing fresh/non-skipped source-suite coverage,
JAR SHA-256 and a fingerprint of POM, wrapper and source/test inputs.
`ops/container/image.py build` refuses missing/failed evidence, substituted JARs
or changed build/test inputs. Documentation/operational scripts are not Maven
inputs. It builds with `--no-cache --network=none --platform=linux/amd64` from a
temporary context containing **only Dockerfile and app.jar**. The repository-root
`.dockerignore` denies everything except Dockerfile, so an unsupported direct
root build fails instead of uploading `.env`, source, tests, Git or key files.
The Dockerfile independently verifies the copied JAR checksum. Do not pass real
credentials through build arguments or modify this context policy to include
local configuration.

The runtime is official Eclipse Temurin **21.0.12.1+1 JRE/Jammy**, amd64 manifest
`sha256:8c2dddf1bb2a8455160f4e23080059de5003eddc5cb839130b177c6be0c2cfe0`,
resolved from official registry metadata on 2026-10-15. No `apt upgrade`,
floating `latest` or startup installation is performed. Digest pinning prevents
replacement, not vulnerabilities; review updates and rescan periodically.
The other architecture manifests have **not** been accepted. Do not assume this
image is suitable for an ARM free-tier host.

## Runtime contract

| Setting | Baseline |
|---|---|
| Identity | Numeric UID/GID `10001:10001`, root-owned read-only JAR |
| Process | Exec-form `java ... -jar`, Java PID 1, `SIGTERM`; no shell/upstream certificate wrapper |
| Heap | Initial 128 MiB, maximum 768 MiB |
| Other JVM budgets | Direct 128 MiB, metaspace 256 MiB, code cache 128 MiB; exit on OOM |
| Container | 1536 MiB memory, 2 CPU, 256 PIDs, all capabilities dropped, no-new-privileges |
| Filesystem | Read-only root; `/tmp` tmpfs 128 MiB, mode1777, nodev/nosuid; includes JVM temp space |
| Shutdown | Application 30 seconds **per phase**; container grace120 seconds, not30 total |
| Business listener | 8080 behind the reviewed ingress; image does not publish host ports |
| Management | Prod default loopback9091, no image `EXPOSE`/host publication; private operator access only |

Memory limits include native JVM/thread/TLS/library overhead and tmpfs, not just
heap. These are finite acceptance-test budgets, **not measured capacity/SLOs**.
T14 may revise them only from reviewed measurement. Dockerfile declares the JVM
budget; the runtime/orchestrator must enforce cgroup, filesystem and capability
controls. Running a bare `docker run IMAGE` does not install those controls.

Prod is the image default. Supply mandatory DB/Rabbit credentials, explicit JWT
PEMs and Webhook AES key at runtime, never inside the image. Use read-only
individual secret mounts plus `SPRING_CONFIG_IMPORT=configtree:/run/secrets/`;
set DB_URL/DB_USERNAME, Rabbit host/user, JWT resource locations and the fixed
Webhook egress proxy host/port. See [production configuration](production-engineering.md#6-production-configuration-and-management-t02).
Keep matching JWT keys and the same separately protected AES encryption key
across app recreation/replicas/restores. Generating a new AES key on each startup
breaks persisted endpoint secrets; this task does not implement key rotation.
Missing mandatory config fails closed before database initialization.

Flyway still migrates/validates the existing PostgreSQL schema; Hibernate remains
`validate`. No startup truncation, database recreation or financial-state reset
is added. Logs go to stdout with the existing privacy policy; no log files,
heap/core dumps, raw scan output or key material are uploaded as CI evidence.

## Reproduce and safe evidence

Requirements: JDK21/Maven wrapper, Python3.11+, Linux Docker/Compose supporting
health/completion dependencies, at least the tested 4CPU/~3.65GiB Docker host and enough disk for
temporary image archives and the current advisory DB. Run sequentially to avoid
combining scanner memory use with integration-test containers:

```sh
python3 -B -m unittest discover -s ops/ci/tests -v
python3 -B ops/ci/gate.py verify
python3 -B ops/container/image.py build
python3 -B ops/container/image.py runtime
python3 -B ops/container/image.py scan
python3 -B ops/ci/gate.py security
```

On Windows use `python`, a real JDK21 `JAVA_HOME` and optionally the documented
`FLOWPAY_CI_MAVEN` executable override. Build leaves a local immutable image ID
and JAR-derived convenience tag. Nothing is pushed. Inspect explicit safe JSON
under `target/ci-safe`: verification, image-build, image-runtime, image-scan and
image-sbom.cdx. Each image report names the tested image ID; build records the
JAR/Dockerfile/verification digests. No raw image archive or application logs are
uploaded. CI permits seven-day **explicit** safe artifacts only and still
requires the separate source supply-chain job to pass.

Pinned Syft1.45.0 catalogs OS and nested Java dependencies from `docker image
save`, without a Docker socket mount or network access. A safe CycloneDX
projection keeps package identities, not arbitrary paths/properties or Syft's
certificate/file metadata. The full private inventory still feeds Grype; only
the published projection omits file metadata, not software packages. Pinned
Grype0.120.1 scans that inventory with its own disposable Linux DB volume;
database freshness/integrity/status and native vulnerable/unavailable-DB controls
are mandatory. Only the separate inventory/output directory and policy/DB reach
network-enabled Grype, never image archives, expanded resources or secret
reports. HIGH/CRITICAL/UNKNOWN block with **no image waivers**. Pinned
Gitleaks8.30.1 scans expanded application classes/resources privately, rather
than treating a compressed JAR as secret-free. The build's minimal context and
only-COPY-JAR policy exclude local source/secrets; the independent tracked-source
scan is still required. This is not a forensic secret scan of arbitrary vendor
binaries or all historical image layers. Scanner unavailability is a failure.

The runtime fixture creates a UUID-owned Compose project with no host ports,
disposable PostgreSQL/Rabbit volumes and fresh **external synthetic** secrets.
It never implicitly loads the user's root `.env`. Real production-profile app
requests create an endpoint/payment/refund with real JWT/API-key authentication,
immutable replay, TLS certificate/IP-SAN verification and exact-body HMAC checks.
A merchant is routed on public-looking fixture IP11.203.0.10 inside a completely
internal owned bridge via the unchanged enforcing CONNECT proxy. No traffic goes
to a real merchant; no localhost/private/trust-all exception is enabled.
Do not run overlapping egress fixtures using11.203.0.0/24 concurrently.

Two app stop/start cycles exercise an in-flight delivery claim and unpublished
financial source state while Rabbit is stopped. The receiver checks duplicate
event IDs retain identical bytes. After recovery, SQL verifies exactly one
payment/provider transaction/refund, two balanced Ledger postings/four entries,
two delivered subscriptions, no unpublished Outbox rows and all13 migrations.
JWT reuse and signed delivery after restart demonstrate external-key continuity.
An in-flight HTTP attempt can finish or leave a recoverable lease; no exactly-once
HTTP guarantee or always-completed-at-shutdown promise is made. SIGTERM must exit
without OOM/SIGKILL. The fixture removes only its own project/volumes/secrets.
The local image remains for inspection; no global Docker prune/dev-volume cleanup.

## Acceptance checkpoint

2026-10-15: **implementation/runtime verified; security BLOCKED, T12 not fully
accepted**. No waiver, fixture rewrite or application dependency upgrade.

- Full JDK21.0.12/Maven3.9.16 `clean verify`: **1,405 tests /195 suites**, zero
  failure/error/skip, actual PostgreSQL16.15/RabbitMQ4.3 Testcontainers.
- Focused controls: **55 tests** pass separately on Windows/Python3.14.7 and
  Linux/Python3.13.12, zero skips. Native actionlint, Compose validation, source
  negative/clean controls and complete required Java SAST coverage pass.
- Final clean image build, non-root/read-only/missing-config checks and full
  prod API/Ledger/Webhook SIGTERM/broker-outage/restart/replay/HMAC/key-continuity
  smoke pass. Both app stops exit without OOM/SIGKILL inside the120s grace.
  SQL also checks each posting separately, closed attempt history, four source
  events and no unpublished rows after recovery. Every owned test project,
  volume, network and external synthetic-secret directory is cleaned up.
- Syft catalogs **299 software components**; the safe projection omits4,860
  file-metadata components, not software packages. The **full private inventory**
  feeds Grype. Image application-resource Gitleaks: **0 findings**. This does not
  waive source/example/test findings or prove vendor binaries/history secret-free.
- Fresh valid GrypeDB **v6.1.10**, built **2026-10-15T06:30:36Z**; vulnerable-Log4j
  and absent/offline-DB native controls pass. Actual image scan reports **181
  candidate package/advisory matches**: **6 CRITICAL, 12 HIGH, 107 MEDIUM, 47 LOW,
  9 NEGLIGIBLE**. The18 HIGH/CRITICAL matches fail the gate (exit1), not a tooling
  failure or a clean result. Matching is not proof of reachability/exploitation;
  no fixes or exceptions are inferred from the report.
- Tracked-source rescan (789 files) retains **10 secret-format findings**,
  **0 SAST findings**; native negative/clean controls pass. This remains blocking
  under the owner's explicit no-exception decision. T11's full234-component
  Maven inventory checkpoint is unchanged; T12 reruns source/image checks rather
  than claiming a new full Maven supply-chain acceptance.

Final executable JAR SHA-256:
`6335858b1a201f8870ddfcf847cdad533cdc1ee5830dd83d34c3554a069aef33`.
Final local image ID:
`sha256:3c79ef6c3724bb8f4b9312dfde92f7d6cdd7f696df187ca859cb9867d2f020ac`.
Docker reports image Size 170,364,823 bytes. Verification records source base
`99429a1` plus tracked operational changes; the unchanged application/build/test
input fingerprint is recorded in verification/build JSON. The task commit does
not pretend to be a different compiled JAR. Later independent builds can have
different timestamps/image IDs and need their own matching gate evidence.

Environment: Docker29.6.2/Compose5.3.1, Linux/amd64 Docker VM with 4 CPU and
3,920,220,160 bytes RAM, host Windows/JDK21. Default optional OTLP metrics export
disabled; required real infrastructure coverage remains enabled. Initial
fixture attempts exposed unused monitoring-JAR interpolation, Compose restarting
a one-shot CA dependency, and an incorrect unversioned internal-event SQL
assertion. These fixture issues were corrected with restart/merchant regression
controls; final gates above passed. Unicode certificate/file metadata remains
private rather than weakening the software-package evidence token policy.

No Java/POM/API/schema/locking/TX/state/retry change, no placeholder runtime code.
The full Java suite is not repeated after unchanged operational projection/docs
edits. CI actions have not run remotely on this unpublished phase branch; owner
required-check settings are not changed. Changes stay on
`feature/phase-8-production-engineering`, not pushed or merged. `.env` remains
user-owned/untracked and unread; ignored phase/index edits stay local-only.
T12 cannot be fully accepted while its image security gate or T11 source gate
remains blocked. T13, load/failure/restore testing, public TLS/provider hosting
and frontend integration are separate tasks, not implied by image startup.

References: [official Temurin image](https://hub.docker.com/_/eclipse-temurin),
[Docker digest pinning/build practices](https://docs.docker.com/build/building/best-practices/),
[Spring Boot image packaging](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html),
[Syft inventory](https://oss.anchore.com/docs/guides/sbom/getting-started/),
[Grype archive inputs](https://oss.anchore.com/docs/guides/vulnerability/scan-targets/).
