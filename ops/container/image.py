"""Local-only image gates. No registry writes, app secrets or raw scanner logs."""
import argparse
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import time
import uuid
import zipfile

SPEC = importlib.util.spec_from_file_location("ci_gate", Path(__file__).resolve().parents[1] / "ci/gate.py")
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)
ROOT = gate.ROOT
SAFE = gate.SAFE


def verified_jar():
    report = gate.document(SAFE / "verification.json")
    jars = list((ROOT / "target").glob("backend-*.jar"))
    gate.require(report.get("status") == "passed" and len(jars) == 1, "clean verification required before image build")
    jar = jars[0]
    gate.require(not jar.is_symlink(), "JAR must not be a symlink")
    gate.require(report.get("jarSha256") == gate.sha256(jar), "JAR differs from clean-verified artifact")
    gate.require(report.get("applicationInputsSha256") == gate.application_inputs_digest(ROOT), "build/test inputs changed since verification")
    with zipfile.ZipFile(jar) as package:
        names = package.namelist()
        gate.require("BOOT-INF/classes/com/flowpay/backend/FlowPayApplication.class" in names, "not an executable application JAR")
        gate.require(not any(name.endswith((".pem", ".key", ".p12", ".java")) or Path(name).name == ".env"
                             or name.startswith(("src/", "BOOT-INF/classes/com/flowpay/backend/testing/")) for name in names),
                     "source, test harness or key file bundled in application")
    return jar


def build(evidence):
    evidence["stage"] = "verified-artifact-build"
    print("image: building exact verified artifact", flush=True)
    jar = verified_jar()
    identity = gate.sha256(jar)
    tag = "flowpay:verified-" + identity[:16]
    with tempfile.TemporaryDirectory(prefix="flowpay-image-context-") as directory:
        context = Path(directory)
        shutil.copyfile(ROOT / "Dockerfile", context / "Dockerfile")
        shutil.copyfile(jar, context / "app.jar")
        gate.run(["docker", "build", "--no-cache", "--network=none", "--platform=linux/amd64",
                  "--build-arg", "JAR_SHA256=" + identity, "--tag", tag, str(context)], timeout=600)
    raw, _ = gate.run(["docker", "image", "inspect", tag])
    image = json.loads(raw)[0]
    gate.require(image["Os"] == "linux" and image["Architecture"] == "amd64", "unverified image platform")
    config = image["Config"]
    gate.require(config["User"] == "10001:10001" and config["Entrypoint"][0] == "java"
                 and config["Entrypoint"][-2:] == ["-jar", "/opt/flowpay/app.jar"], "unsafe image identity or signal path")
    gate.require(config.get("StopSignal") == "SIGTERM" and not config.get("Volumes"), "unexpected image signal or writable volume")
    evidence.update(imageId=gate.token(image["Id"]), tag=tag, jarSha256=identity,
                    dockerfileSha256=gate.sha256(ROOT / "Dockerfile"), platform="linux/amd64",
                    verificationSha256=gate.sha256(SAFE / "verification.json"), bytes=image["Size"],
                    applicationInputsSha256=gate.application_inputs_digest(ROOT))
    # Check actual runtime, not only image configuration. Missing prod settings
    # must fail before any external database connection; logs remain private.
    name = "flowpay-ci-" + uuid.uuid4().hex
    command = ["docker", "run", "--rm", "--name", name, "--label", "flowpay.ci.owner=" + name,
               "--network=none", "--read-only", "--cap-drop=ALL",
               "--security-opt=no-new-privileges", "--memory=1536m", "--cpus=2", "--pids-limit=256",
               "--tmpfs=/tmp:rw,nosuid,nodev,size=128m", image["Id"]]
    rejected, _ = gate.run(command, allowed=(1,), timeout=60)
    gate.require(b"Invalid production configuration:" in rejected, "missing configuration must fail through the production guard")
    output, _ = gate.run(command[:-1] + ["--entrypoint=/bin/sh", image["Id"], "-ec",
                         'test "$(id -u)" = 10001; test "$(cat /proc/1/comm)" = sh; '
                         'test ! -w /opt/flowpay/app.jar; test ! -w /opt/flowpay; '
                         'test -w /tmp; test ! -e /run/secrets; java -version 2>&1'], timeout=30)
    version = re.search(rb'version "(21\.[0-9A-Za-z.+_-]+)"', output)
    gate.require(version is not None, "image must actually run Java 21")
    evidence.update(javaVersion=version[1].decode(), nonRootReadOnlyAndMissingConfig="passed")


def image_identity():
    evidence = gate.document(SAFE / "image-build.json")
    gate.require(evidence.get("status") == "passed", "successful image build evidence required")
    gate.require(evidence.get("jarSha256") == gate.sha256(verified_jar())
                 and evidence.get("dockerfileSha256") == gate.sha256(ROOT / "Dockerfile"), "stale image evidence")
    return gate.token(evidence["imageId"])


def start_owned_container(project, state):
    gate.require(state["Config"]["Labels"].get("com.docker.compose.project") == project
                 and re.fullmatch(r"[0-9a-f]{64}", state["Id"]),
                 "refusing to start a container outside the owned project")
    # Compose start can also restart completed dependencies. Never reinitialize
    # a CA or key when testing state continuity: start only this exact container.
    gate.run(["docker", "start", state["Id"]], timeout=60)


def safe_image_bom(bom):
    gate.require(bom.get("bomFormat") == "CycloneDX" and bool(bom.get("components")), "missing image SBOM inventory")
    components = []
    for item in bom["components"]:
        # Syft also emits certificate/file inventory with filesystem names
        # (including Unicode CA filenames). Those are not package identities.
        # Keep them in the private full scanner input, not the public projection.
        if item.get("type") == "file":
            continue
        # Deliberately strip paths, arbitrary properties, descriptions and source metadata.
        components.append({key: gate.token(item[key]) for key in ("type", "group", "name", "version", "purl", "bom-ref") if key in item})
    gate.require(any(item.get("purl", "").startswith("pkg:maven/") for item in components)
                 and any(item.get("purl", "").startswith("pkg:deb/") for item in components), "image SBOM must include OS and Java packages")
    return {"bomFormat": "CycloneDX", "specVersion": gate.token(bom["specVersion"]), "version": 1, "components": components}


def vulnerability_mounts(inventory):
    # This directory alone reaches network-enabled Grype. No image archive,
    # expanded application resources, source or secret report belongs here.
    gate.require({path.name for path in inventory.iterdir()} == {"image.cdx.json"}
                 and (inventory / "image.cdx.json").is_file()
                 and not (inventory / "image.cdx.json").is_symlink(), "network scanner input must be inventory only")
    return [(inventory, "/out", True), (gate.POLICY / "grype.yaml", "/policy/grype.yaml", False)]


def scan(evidence):
    evidence["stage"] = "image-inventory"
    print("image: cataloging OS and Java packages", flush=True)
    image = image_identity()
    evidence["imageId"] = image
    with tempfile.TemporaryDirectory(prefix="flowpay-image-scan-") as directory:
        work = Path(directory)
        archive = work / "image.tar"
        inventory = work / "inventory"
        inventory.mkdir()
        secret_reports = work / "secret-reports"
        secret_reports.mkdir()
        gate.run(["docker", "image", "save", "--output", str(archive), image], timeout=120)
        for tool in ("syft", "grype", "gitleaks"):
            gate.run(["docker", "pull", gate.TOOLS[tool]], timeout=300)
        evidence["tools"] = {tool: gate.TOOLS[tool] for tool in ("syft", "grype", "gitleaks")}
        gate.run(gate.container("syft", [(archive, "/input/image.tar", False), (inventory, "/out", True)],
                               ["scan", "docker-archive:/input/image.tar", "-o", "cyclonedx-json=/out/image.cdx.json"],
                               env=["SYFT_CHECK_FOR_APP_UPDATE=false"]), timeout=300)
        catalog = gate.document(inventory / "image.cdx.json")
        safe = safe_image_bom(catalog)
        gate.write_json(SAFE / "image-sbom.cdx.json", safe)
        evidence.update(sbomComponents=len(safe["components"]),
                        omittedFileMetadataComponents=len(catalog["components"]) - len(safe["components"]),
                        sbomSha256=gate.sha256(SAFE / "image-sbom.cdx.json"))
        # The only project artifact in the image is the executable JAR. Expand
        # it privately so the secret scanner sees resources, not compressed bytes.
        content = work / "jar-content"
        content.mkdir()
        with zipfile.ZipFile(verified_jar()) as package:
            for name in package.namelist():
                if name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                    relative = Path(name.removeprefix("BOOT-INF/classes/"))
                    gate.require(not relative.is_absolute() and ".." not in relative.parts, "unsafe JAR resource path")
                    path = content / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(package.read(name))
        _, code = gate.run(gate.container("gitleaks", [(content, "/input", False), (secret_reports, "/out", True),
                                                       (gate.POLICY, "/policy", False)],
                              ["dir", "/input", "--config", "/policy/gitleaks.toml", "--no-banner", "--redact=100",
                               "--exit-code", "17", "--report-format", "json", "--report-path", "/out/secrets.json"]), allowed=(0, 17))
        leaks = gate.document(secret_reports / "secrets.json")
        gate.require(isinstance(leaks, list) and bool(leaks) == (code == 17), "inconsistent image secret scan")
        evidence["applicationSecretFindings"] = len(leaks)
        evidence["stage"] = "image-vulnerability-scan"
        print("image: scanning with a fresh vulnerability database", flush=True)
        with gate.scanner_database() as cache:
            mounts = vulnerability_mounts(inventory)
            gate.run(gate.container("grype", mounts, ["--config", "/policy/grype.yaml", "sbom:/out/image.cdx.json",
                          "--output", "json", "--file", "/out/findings.json"], network="bridge",
                          env=["GRYPE_DB_CACHE_DIR=/db"], database_volume=cache), timeout=600)
            status, _ = gate.run(gate.container("grype", [(gate.POLICY / "grype.yaml", "/policy/grype.yaml", False)],
                                 ["--config", "/policy/grype.yaml", "db", "status", "--output", "json"],
                                 env=["GRYPE_DB_CACHE_DIR=/db", "GRYPE_DB_AUTO_UPDATE=false", "GRYPE_DB_REQUIRE_UPDATE_CHECK=false"], database_volume=cache))
            findings, database = gate.dependency_findings(gate.document(inventory / "findings.json"), json.loads(status), dt.datetime.now(dt.timezone.utc))
            gate.dependency_controls(work, cache)
        evidence.update(findings=findings, database=database, nativeDependencyControls="passed")
        # No image exceptions: user explicitly chose to leave the security gate blocked.
        gate.require(not leaks and not any(item["blocking"] for item in findings), "unapproved image security findings; release blocked")


def runtime(evidence):
    image = image_identity()
    evidence["imageId"] = image
    project = "flowpay-image-" + uuid.uuid4().hex
    compose_file = ROOT / "ops/container/tests/compose.yaml"
    with tempfile.TemporaryDirectory(prefix="flowpay-image-runtime-") as directory:
        work = Path(directory)
        secret = work / "secrets"
        secret.mkdir()
        empty_env = work / "empty.env"
        empty_env.touch()
        # Never let Compose implicitly load the user's root .env.
        command = ["docker", "compose", "--env-file", str(empty_env), "--project-name", project,
                   "--file", str(compose_file)]
        previous = {key: os.environ.get(key) for key in ("LAB_SECRETS_DIR", "FLOWPAY_IMAGE")}
        os.environ.update(LAB_SECRETS_DIR=str(secret), FLOWPAY_IMAGE=image)
        owned = False
        configured = False

        def compose(*arguments, timeout=180):
            probe = len(arguments) >= 2 and arguments[-2] == "probe"
            result = gate.run(command + list(arguments), timeout=timeout, allowed=tuple(range(256)))
            if probe and result[1]:
                # Only our fixed probe failure labels/numeric status are public.
                match = re.search(rb"FAIL: (owned (?:status expected \d+, got \d+|merchant signature invariant failed|readiness/delivery deadline exceeded|image assertion failed))", result[0])
                raise gate.GateError(match[1].decode() if match else "owned image probe failed; raw output withheld")
            gate.require(result[1] == 0, "owned Compose operation failed with exit code " + str(result[1]))
            return result

        def service_state(service):
            container_id, _ = compose("ps", "--all", "--quiet", service)
            raw, _ = gate.run(["docker", "inspect", container_id.decode().strip()])
            return json.loads(raw)[0]

        def app_state():
            return service_state("app")

        def start_existing(service):
            start_owned_container(project, service_state(service))

        def sql(statement):
            raw, _ = compose("exec", "-T", "postgres", "psql", "-U", "flowpay_lab", "-d", "flowpay_lab",
                             "-v", "ON_ERROR_STOP=1", "-Atc", statement)
            return raw.strip() == b"t"

        def stopped():
            state = app_state()["State"]
            gate.require(not state["Running"] and not state["OOMKilled"] and state["ExitCode"] in (0, 143),
                         "SIGTERM did not stop image cleanly; forced kill/OOM is not acceptance")

        try:
            evidence["stage"] = "owned-runtime-startup"
            print("image: starting isolated prod-profile runtime", flush=True)
            for resource in ("container", "volume", "network"):
                raw, _ = gate.run(["docker", resource, "ls", "--quiet", "--filter", "label=com.docker.compose.project=" + project])
                gate.require(not raw.strip(), "runtime project must own fresh resources")
            owned = True
            evidence["stage"] = "owned-secret-generation"
            base = re.search(r"^FROM (.+)$", (ROOT / "Dockerfile").read_text(), re.M)[1]
            user = ["--user", f"{os.getuid()}:{os.getgid()}"] if os.name != "nt" else []
            generator = "flowpay-ci-" + uuid.uuid4().hex
            gate.run(["docker", "run", "--rm", "--name", generator, "--label", "flowpay.ci.owner=" + generator, "--network=none"]
                     + user + ["--mount", f"type=bind,source={secret},target=/secrets",
                      "--mount", f"type=bind,source={ROOT / 'ops/monitoring/tests/init-secrets.sh'},target=/init.sh,readonly",
                      "--entrypoint=sh", base, "/init.sh", "/secrets"], timeout=60)
            key_hash = gate.sha256(secret / "WEBHOOK_SECRET_ENCRYPTION_KEY")
            evidence["stage"] = "compose-validation"
            compose("config", "--quiet")
            configured = True
            evidence["stage"] = "owned-runtime-startup"
            compose("up", "--detach", timeout=300)
            config = app_state()
            host = config["HostConfig"]
            gate.require(config["Image"] == image and config["Path"] == "java" and config["Config"]["User"] == "10001:10001",
                         "fixture must execute the built image directly as non-root")
            gate.require(host["ReadonlyRootfs"] and host["Memory"] == 1536 * 1024 * 1024 and host["NanoCpus"] == 2_000_000_000
                         and host["PidsLimit"] == 256 and "ALL" in host["CapDrop"] and not host.get("PortBindings"),
                         "runtime sandbox/resource boundary mismatch")
            gate.require(not any(mount["Destination"] == "/opt/flowpay/app.jar" for mount in config["Mounts"]), "host JAR must not mask tested image")
            compose("exec", "-T", "app", "sh", "-ec", 'test "$(cat /proc/1/comm)" = java; test "$(id -u)" = 10001')
            evidence["stage"] = "api-and-active-webhook"
            compose("--profile", "checks", "run", "--rm", "probe", "prepare", timeout=180)
            gate.require(sql("SELECT EXISTS(SELECT 1 FROM webhook_deliveries WHERE status='DELIVERING')"), "shutdown must exercise an active worker claim")
            compose("stop", "--timeout", "120", "app")
            stopped()
            evidence["inFlightWorkerSigterm"] = "passed; completed or recoverable durable claim"
            evidence["stage"] = "broker-outage-source"
            print("image: testing broker outage and persisted source recovery", flush=True)
            evidence["stage"] = "first-image-restart"
            start_existing("app")
            # The source must still commit financial state while Rabbit is down.
            evidence["stage"] = "restart-readiness"
            compose("--profile", "checks", "run", "--rm", "probe", "ready", timeout=180)
            evidence["stage"] = "broker-stop"
            compose("stop", "rabbitmq")
            evidence["stage"] = "broker-outage-source"
            compose("--profile", "checks", "run", "--rm", "probe", "refund", timeout=180)
            evidence["stage"] = "unpublished-source-sql"
            gate.require(sql("SELECT EXISTS(SELECT 1 FROM outbox_events WHERE event_type='refund.succeeded.v1' AND published_at IS NULL)"),
                         "broker-outage refund must leave durable unpublished source")
            evidence["stage"] = "outage-sigterm"
            compose("stop", "--timeout", "120", "app")
            stopped()
            # Restart existing services only: never rerun the one-shot CA/key
            # initializer or recreate state while demonstrating continuity.
            evidence["stage"] = "broker-restart-health"
            start_existing("rabbitmq")
            deadline = time.monotonic() + 120
            while service_state("rabbitmq")["State"].get("Health", {}).get("Status") != "healthy":
                gate.require(time.monotonic() < deadline, "broker restart health deadline exceeded")
                time.sleep(1)
            start_existing("app")
            evidence["stage"] = "restart-ledger-webhook-replay"
            compose("--profile", "checks", "run", "--rm", "probe", "verify", timeout=180)
            statement = """SELECT
                (SELECT COUNT(*)=1 FROM payment_intents WHERE status='PARTIALLY_REFUNDED'
                    AND refunded_amount_minor=1000 AND refund_reserved_minor=0)
                AND (SELECT COUNT(*)=1 FROM payment_transactions)
                AND (SELECT COUNT(*)=1 FROM refunds WHERE status='SUCCEEDED')
                AND (SELECT COUNT(*)=2 FROM ledger_transactions)
                AND (SELECT COUNT(*)=4 FROM ledger_entries)
                AND (SELECT SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END)=0 FROM ledger_entries)
                AND NOT EXISTS(SELECT 1 FROM ledger_entries GROUP BY ledger_transaction_id
                    HAVING COUNT(*)<>2 OR SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END)<>0)
                AND (SELECT COUNT(*)=2 FROM webhook_deliveries WHERE status='DELIVERED')
                AND NOT EXISTS(SELECT 1 FROM webhook_delivery_attempts WHERE finished_at IS NULL)
                AND (SELECT COUNT(*)=4 FROM outbox_events)
                AND NOT EXISTS(SELECT 1 FROM outbox_events WHERE published_at IS NULL)
                AND (SELECT COUNT(*)=13 FROM flyway_schema_history WHERE success)"""
            deadline = time.monotonic() + 120
            while not sql(statement):
                gate.require(time.monotonic() < deadline, "durable business/ledger/delivery recovery failed")
                time.sleep(1)
            gate.require(key_hash == gate.sha256(secret / "WEBHOOK_SECRET_ENCRYPTION_KEY"), "external encryption key changed across restart")
            evidence.update(runtimeSandbox="passed", apiLedgerWebhookE2E="passed", brokerOutageRestartRecovery="passed",
                            exactReplayAndHmacAcrossRestart="passed", persistentExternalKeys="passed", migrations=13)
        finally:
            try:
                # UUID project, proven absent before creation; do not touch shared/dev projects.
                if owned and configured:
                    compose("--profile", "checks", "down", "--volumes", "--remove-orphans", timeout=180)
                    for resource in ("container", "volume", "network"):
                        raw, _ = gate.run(["docker", resource, "ls", "--quiet", "--filter", "label=com.docker.compose.project=" + project])
                        gate.require(not raw.strip(), "owned runtime resources remain after cleanup")
                    evidence["ownedResourceCleanup"] = "passed"
            finally:
                for key, value in previous.items():
                    if value is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("gate", choices=("build", "scan", "runtime"))
    args = parser.parse_args()
    evidence = {"status": "incomplete", "gate": "image-" + args.gate, "timestamp": dt.datetime.now(dt.timezone.utc).isoformat()}
    path = SAFE / ("image-" + args.gate + ".json")
    path.unlink(missing_ok=True)
    if args.gate == "scan":
        (SAFE / "image-sbom.cdx.json").unlink(missing_ok=True)
    try:
        {"build": build, "scan": scan, "runtime": runtime}[args.gate](evidence)
        evidence["status"] = "passed"
    except gate.GateError as error:
        evidence.update(status="failed", reason=str(error))
    except Exception:
        evidence.update(status="failed", reason="unexpected image gate failure; raw output withheld")
    gate.write_json(path, evidence)
    print(f"image-{args.gate}: {evidence['status']} (safe evidence: {path.relative_to(ROOT)})")
    return 0 if evidence["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
