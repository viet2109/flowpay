"""Fail-closed CI gates. Never echo subprocess logs, matched secrets or test output.

Raw temporary output is deleted on exit. Only explicit allow-list projections go
to target/ci-safe. No cloud scanner service, Docker socket mount or local .env.
"""
import argparse
from contextlib import contextmanager
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import tarfile
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / "ops/ci"
SAFE = ROOT / "target/ci-safe"
TOOLS = json.loads((POLICY / "tools.json").read_text())


class GateError(Exception):
    """Fixed non-sensitive failure labels only."""


def require(condition, label):
    if not condition:
        raise GateError(label)


def run(command, *, cwd=ROOT, timeout=600, allowed=(0,)):
    # Not inherited by scanner containers. CI has no write token or app secrets.
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        try:
            result = subprocess.run(command, cwd=cwd, stdout=stdout, stderr=stderr,
                                    timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired) as error:
            cleanup_container(command)
            raise GateError("command unavailable or timed out") from error
        require(result.returncode in allowed, "command failed; raw output withheld")
        stdout.seek(0)
        return stdout.read(), result.returncode


def cleanup_container(command):
    # Killing a Docker CLI on timeout does not necessarily stop its container.
    # Remove only this exact invocation's name after verifying its unique label.
    if command[:2] != ["docker", "run"] or "--name" not in command:
        return
    name = command[command.index("--name") + 1]
    if not re.fullmatch(r"flowpay-ci-[a-f0-9]{32}", name):
        return
    try:
        inspected = subprocess.run(["docker", "inspect", "--format", '{{index .Config.Labels "flowpay.ci.owner"}}', name],
                                   capture_output=True, timeout=20, check=False)
        if inspected.returncode == 0 and inspected.stdout.decode().strip() == name:
            subprocess.run(["docker", "rm", "--force", name], capture_output=True, timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired):
        pass  # The original gate fails; never broaden cleanup to other containers.


def document(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise GateError("missing or malformed scanner report") from error


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def preflight():
    output, _ = run(["docker", "info", "--format", "{{.OSType}}"], timeout=30)
    require(output.strip() == b"linux", "Linux Docker daemon required; no skipped infrastructure acceptance")
    # java -version writes to stderr; properties are captured, not published.
    try:
        java = subprocess.run(["java", "-version"], capture_output=True, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise GateError("JDK 21 required") from error
    require(java.returncode == 0 and re.search(rb'version "21[.+"]', java.stderr + java.stdout),
            "JDK 21 required")
    # PATH java and JAVA_HOME can disagree (e.g. an Android Studio JBR on Windows).
    # Check the JVM Maven really uses before clean/build touches prior evidence.
    output, _ = maven(["--version"], timeout=30)
    version = re.search(rb"Java version: (21\.[0-9A-Za-z.+_-]+)", output)
    require(version is not None, "Maven must actually run on JDK 21; check JAVA_HOME")
    return {"mavenJavaVersion": version[1].decode("ascii")}


def maven(arguments, timeout=1200):
    # The override is a local executable path, never arbitrary Maven arguments.
    override = os.environ.get("FLOWPAY_CI_MAVEN")
    command = [override] if override else (["cmd", "/c", str(ROOT / "mvnw.cmd")] if os.name == "nt"
                                          else ["sh", str(ROOT / "mvnw")])
    return run(command + ["--batch-mode", "--no-transfer-progress",
                          "-Dmanagement.otlp.metrics.export.enabled=false"] + arguments, timeout=timeout)


def expected_suites(root):
    expected = set()
    for path in (root / "src/test/java").rglob("*.java"):
        if not (path.name.startswith("Test") or path.stem.endswith(("Test", "Tests", "TestCase"))):
            continue
        source = path.read_text(encoding="utf-8")
        if re.search(r"\babstract\s+class\s+" + re.escape(path.stem) + r"\b", source):
            continue
        package = re.search(r"^package\s+([\w.]+);", source, re.M)
        require(package is not None, "test source package cannot be identified")
        expected.add(package[1] + "." + path.stem)
    require(bool(expected), "empty required-test inventory")
    return expected


def verify_reports(root, started):
    expected = expected_suites(root)
    seen = set()
    totals = dict(tests=0, failures=0, errors=0, skipped=0, suites=0)
    for path in (root / "target/surefire-reports").glob("TEST-*.xml"):
        require(path.stat().st_mtime >= started - 2, "stale test report")
        try:
            suite = ET.parse(path).getroot()
            require(suite.tag == "testsuite", "unexpected test report format")
            name = suite.attrib["name"].split("$")[0]
            counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            require(all(value >= 0 for value in counts.values()), "negative test counters")
            require(counts["tests"] > 0, "empty test suite")
            require(not any(counts[key] for key in ("failures", "errors", "skipped")),
                    "failed, erroneous or skipped tests are not acceptance")
            require(not any(True for tag in ("skipped", "failure", "error") for _ in suite.iter(tag)),
                    "non-passing testcase detected")
            seen.add(name)
            for key, value in counts.items():
                totals[key] += value
            totals["suites"] += 1
        except (OSError, ET.ParseError, KeyError, ValueError) as error:
            raise GateError("invalid test report") from error
    require(expected <= seen, "required source suites missing, including infrastructure coverage")
    return totals | {"requiredSourceSuites": len(expected)}


def verify(evidence):
    evidence["runtime"] = preflight()
    lint_workflow()
    evidence["workflowValidation"] = "actionlint passed"
    started = time.time()
    maven(["clean", "verify"], timeout=1500)
    evidence["coverage"] = verify_reports(ROOT, started)
    jars = list((ROOT / "target").glob("backend-*.jar"))
    require(len(jars) == 1, "expected exactly one executable JAR")
    evidence["jarSha256"] = sha256(jars[0])
    evidence["applicationInputsSha256"] = application_inputs_digest(ROOT)


def application_inputs_digest(root):
    """Bind packaging evidence to build/test inputs, not later documentation edits."""
    paths = [root / name for name in ("pom.xml", "mvnw", "mvnw.cmd")]
    for name in ("src", ".mvn"):
        paths += [path for path in (root / name).rglob("*") if path.is_file()]
    digest = hashlib.sha256()
    for path in sorted(paths):
        require(not path.is_symlink(), "symlink build input")
        digest.update(path.relative_to(root).as_posix().encode() + b"\0" + sha256(path).encode() + b"\0")
    return digest.hexdigest()


def lint_workflow():
    platform = "windows" if os.name == "nt" else "linux"
    pin = TOOLS["actionlint"]
    archive_name = f"actionlint_{pin['version']}_{platform}_amd64." + ("zip" if platform == "windows" else "tar.gz")
    with tempfile.TemporaryDirectory(prefix="flowpay-actionlint-") as directory:
        archive = Path(directory) / archive_name
        try:
            with urllib.request.urlopen(f"https://github.com/rhysd/actionlint/releases/download/v{pin['version']}/{archive_name}",
                                        timeout=60) as response:
                archive.write_bytes(response.read(20 * 1024 * 1024))
        except OSError as error:
            raise GateError("workflow validator download unavailable") from error
        require(sha256(archive) == pin[platform + "Sha256"], "workflow validator checksum mismatch")
        executable = Path(directory) / ("actionlint.exe" if platform == "windows" else "actionlint")
        if platform == "windows":
            with zipfile.ZipFile(archive) as package:
                executable.write_bytes(package.read("actionlint.exe"))
        else:
            with tarfile.open(archive) as package:
                executable.write_bytes(package.extractfile("actionlint").read())
            executable.chmod(0o700)
        run([str(executable), "-shellcheck=", "-pyflakes=", str(ROOT / ".github/workflows/ci.yml")], timeout=30)


def tracked_snapshot(destination):
    files, _ = run(["git", "ls-files", "-z"])
    count = 0
    for name in files.decode("utf-8").split("\0"):
        if not name:
            continue
        relative = Path(name)
        require(not relative.is_absolute() and ".." not in relative.parts, "unsafe tracked path")
        require(name not in (".env", "AGENTS.md", "AGENTS.override.md", "docs/README.md")
                and not re.fullmatch(r"docs/phase-.*\.md", name)
                and relative.name not in ("AGENTS.md", "AGENTS.override.md"),
                "local-only file is tracked; remove it from Git before CI")
        source = ROOT / relative
        require(not source.is_symlink() and source.is_file() and source.resolve().is_relative_to(ROOT.resolve()),
                "missing or symlink tracked source")
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        count += 1
    require(count > 0, "empty tracked snapshot")
    return count


def snapshot_digest(source):
    digest = hashlib.sha256()
    for path in sorted(source.rglob("*")):
        if path.is_file():
            digest.update(path.relative_to(source).as_posix().encode() + b"\0" + sha256(path).encode() + b"\0")
    return digest.hexdigest()


def scanner_identity():
    # Linux bind outputs belong to the host runner, not root. With all
    # capabilities dropped, root cannot bypass a different UID's 0755 directory.
    # Docker Desktop's Windows bind semantics have no corresponding POSIX UID.
    if not hasattr(os, "getuid"):
        return None
    return os.getuid(), os.getgid()


def container(tool, mounts, args, *, network="none", env=(), database_volume=None):
    image = TOOLS[tool]
    require(re.fullmatch(r"[^@]+@sha256:[0-9a-f]{64}", image) is not None, "scanner image must be digest-pinned")
    name = "flowpay-ci-" + uuid.uuid4().hex
    command = ["docker", "run", "--rm", "--name", name, "--label", "flowpay.ci.owner=" + name,
               "--network", network, "--read-only", "--cap-drop", "ALL",
               "--security-opt", "no-new-privileges", "--memory", "1536m", "--cpus", "2",
               "--pids-limit", "256", "--tmpfs", "/tmp:rw,nosuid,nodev,size=512m,mode=1777", "-e", "HOME=/tmp"]
    identity = scanner_identity()
    if identity is not None:
        command += ["--user", f"{identity[0]}:{identity[1]}"]
    for value in env:
        command += ["-e", value]
    for source, destination, writable in mounts:
        command += ["--mount", f"type=bind,source={source.resolve()},target={destination}" + ("" if writable else ",readonly")]
    if database_volume:
        require(re.fullmatch(r"flowpay-ci-[a-f0-9]{32}", database_volume), "unsafe database volume name")
        command += ["--mount", f"type=volume,source={database_volume},target=/db"]
    return command + [image] + args


def initialize_scanner_database(name, identity):
    """Assign only a new empty invocation-owned cache to the Linux runner UID."""
    require(re.fullmatch(r"flowpay-ci-[a-f0-9]{32}", name), "unsafe database volume name")
    output, _ = run(["docker", "volume", "inspect", "--format", '{{index .Labels "flowpay.ci.owner"}}', name], timeout=30)
    require(output.decode().strip() == name, "database volume ownership mismatch; initialization aborted")
    require(len(identity) == 2 and all(type(value) is int and value >= 0 for value in identity),
            "invalid scanner cache owner")
    image = TOOLS["volume-init"]
    require(re.fullmatch(r"[^@]+@sha256:[0-9a-f]{64}", image), "cache initializer must be digest-pinned")
    # No source, reports, credentials, host path, socket or network reaches this
    # short-lived helper. CHOWN is never granted to scanners. No recursive chown.
    script = ("import os,stat; p='/db'; s=os.lstat(p); "
              "assert stat.S_ISDIR(s.st_mode) and not os.listdir(p), 'cache must be empty'; "
              f"os.chmod(p,0o700); os.chown(p,{identity[0]},{identity[1]})")
    helper = "flowpay-ci-" + uuid.uuid4().hex
    run(["docker", "run", "--rm", "--name", helper, "--label", "flowpay.ci.owner=" + helper,
         "--user", "0:0", "--network", "none", "--read-only", "--cap-drop", "ALL", "--cap-add", "CHOWN",
         "--security-opt", "no-new-privileges", "--memory", "64m", "--cpus", "1", "--pids-limit", "32",
         "--mount", f"type=volume,source={name},target=/db", image, "python", "-B", "-c", script], timeout=120)


@contextmanager
def scanner_database():
    # SQLite stays on Linux storage, not a Windows host bind mount. No shared cache.
    name = "flowpay-ci-" + uuid.uuid4().hex
    run(["docker", "volume", "create", "--label", "flowpay.ci.owner=" + name, name], timeout=30)
    try:
        identity = scanner_identity()
        if identity is not None and identity[0] != 0:
            initialize_scanner_database(name, identity)
        yield name
    finally:
        output, _ = run(["docker", "volume", "inspect", "--format", '{{index .Labels "flowpay.ci.owner"}}', name], timeout=30)
        require(output.decode().strip() == name, "database volume ownership mismatch; cleanup aborted")
        run(["docker", "volume", "rm", name], timeout=30)


def source_scans(source, output, *, negative=False):
    mounts = [(source, "/src", False), (POLICY, "/policy", False), (output, "/out", True)]
    _, leak_code = run(container("gitleaks", mounts, ["dir", "/src", "--config", "/policy/gitleaks.toml",
                                "--no-banner", "--redact=100", "--ignore-gitleaks-allow", "--exit-code", "17", "--report-format", "json",
                                "--report-path", "/out/secrets.json"], env=["GITLEAKS_CONFIG=/policy/gitleaks.toml"]),
                                allowed=(0, 17))
    leaks = document(output / "secrets.json")
    require(isinstance(leaks, list), "invalid secret report")
    require((leak_code == 17) == bool(leaks), "inconsistent secret scanner status")
    _, sast_code = run(container("semgrep", mounts, ["semgrep", "scan", "--config", "/policy/semgrep.yaml",
                                "--metrics=off", "--disable-version-check", "--disable-nosem", "--strict",
                                "--error", "--no-git-ignore", "--jobs", "2", "--timeout", "20",
                                "--json-output", "/out/sast.json", "/src" if negative else "/src/src/main/java"],
                                env=["SEMGREP_SEND_METRICS=off", "SEMGREP_ENABLE_VERSION_CHECK=0"]), allowed=(0, 1))
    sast = document(output / "sast.json")
    require(isinstance(sast.get("results"), list) and not sast.get("errors"), "SAST incomplete or malformed")
    require(bool(sast.get("paths", {}).get("scanned")), "SAST scanned no files")
    expected_java = {"/src/" + path.relative_to(source).as_posix()
                     for path in (source if negative else source / "src/main/java").rglob("*.java")}
    require(bool(expected_java) and expected_java <= set(sast["paths"]["scanned"]),
            "SAST skipped required Java source files")
    require((sast_code == 1) == bool(sast["results"]), "inconsistent SAST scanner status")
    return leaks, sast


def negative_scanners(work):
    source, output = work / "fixture", work / "fixture-output"
    source.mkdir()
    output.mkdir()
    # Deliberately nonfunctional, disposable strings. Never a live credential.
    (source / "canary.txt").write_text("FLOWPAY_TEST_ONLY_" + "Z" * 24
                                      + '\naws_access_key_id = "' + "AKIA" + "QWERTYUIOPASDFGH" + '" # gitleaks:allow\n')
    (source / "Unsafe.java").write_text('''import java.io.*;
import java.security.*;
class Unsafe {
  void unsafe(String input) throws Exception {
    Runtime.getRuntime().exec(input);
    new ProcessBuilder(input);
    new ObjectInputStream(System.in);
    MessageDigest.getInstance("MD5");
    Object verifier = NoopHostnameVerifier.INSTANCE;
    statement.executeQuery("SELECT * FROM users WHERE id=" + input);
    Cipher.getInstance("AES/ECB/PKCS5Padding");
    new Yaml();
  }
}
''')
    leaks, sast = source_scans(source, output, negative=True)
    require(any(item.get("RuleID") == "flowpay-negative-canary" for item in leaks), "secret canary not detected")
    require(any(item.get("RuleID") == "aws-access-token" for item in leaks), "built-in secret detection control failed")
    rules = {item["check_id"].split(".")[-1] for item in sast["results"]}
    require(rules == {"java-process-execution", "java-sql-concatenation", "java-native-deserialization",
                      "java-trust-all-tls", "java-weak-digest", "java-weak-cipher", "java-unsafe-yaml"},
            "not all Java SAST canaries detected")
    (source / "canary.txt").unlink()
    (source / "Unsafe.java").write_text('class Safe { int value() { return 1; } }')
    leaks, sast = source_scans(source, output, negative=True)
    require(not leaks and not sast["results"], "clean scanner control failed")


def token(value):
    require(isinstance(value, str) and len(value) <= 600
            and re.fullmatch(r"[A-Za-z0-9_./:@+%?=&$~!()\[\],;-]+", value), "invalid safe evidence token")
    return value


def safe_bom(bom):
    require(bom.get("bomFormat") == "CycloneDX" and isinstance(bom.get("components"), list)
            and bool(bom["components"]), "empty or invalid dependency SBOM")
    components = []
    for item in bom["components"]:
        require(item.get("purl", "").startswith("pkg:maven/"), "non-Maven dependency inventory requires review")
        components.append({key: token(item[key]) for key in ("type", "group", "name", "version", "purl", "bom-ref")
                           if key in item})
    metadata = bom["metadata"]["component"]
    return {"bomFormat": "CycloneDX", "specVersion": token(bom["specVersion"]), "version": 1,
            "metadata": {"component": {key: token(metadata[key]) for key in ("type", "group", "name", "version", "bom-ref")
                                       if key in metadata}},
            "components": components,
            "dependencies": [{"ref": token(item["ref"]), "dependsOn": [token(ref) for ref in item["dependsOn"]]}
                             for item in bom.get("dependencies", [])]}


def dependency_findings(report, database, now):
    require(isinstance(report.get("matches"), list) and not report.get("ignoredMatches"), "incomplete dependency report")
    require(database.get("valid") is True and not database.get("error"), "vulnerability database unavailable")
    reported = report.get("descriptor", {}).get("db", {}).get("status", {})
    require(reported.get("valid") is True and not reported.get("error")
            and reported.get("built") == database.get("built")
            and reported.get("schemaVersion") == database.get("schemaVersion"),
            "scan database metadata missing or differs from owned database status")
    try:
        built = dt.datetime.fromisoformat(database["built"].replace("Z", "+00:00"))
        age = now - built
        require(dt.timedelta(0) <= age <= dt.timedelta(hours=72), "vulnerability database stale or future-dated")
    except (KeyError, ValueError, TypeError) as error:
        raise GateError("vulnerability database evidence missing") from error
    findings = []
    for match in report["matches"]:
        vulnerability, artifact = match["vulnerability"], match["artifact"]
        severity = token(vulnerability["severity"]).upper()
        require(severity in {"NEGLIGIBLE", "LOW", "MEDIUM", "HIGH", "CRITICAL", "UNKNOWN"}, "unknown severity schema")
        identity, package = token(vulnerability["id"]), token(artifact["purl"])
        findings.append({"key": f"dependency|{identity}|{package}", "severity": severity,
                         "blocking": severity in {"HIGH", "CRITICAL", "UNKNOWN"}})
    return findings, {"built": built.isoformat(), "schema": token(database["schemaVersion"])}


def dependency_controls(work, cache):
    fixture = work / "dependency-control"
    fixture.mkdir()
    component = {"type": "library", "name": "log4j-core", "group": "org.apache.logging.log4j",
                 "version": "2.14.1", "purl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"}
    write_json(fixture / "canary.cdx.json", {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
                                            "components": [component]})
    mounts = [(fixture, "/fixture", True), (POLICY / "grype.yaml", "/policy/grype.yaml", False)]
    args = ["--config", "/policy/grype.yaml", "sbom:/fixture/canary.cdx.json", "--fail-on", "high",
            "--output", "json", "--file", "/fixture/result.json"]
    env = ["GRYPE_DB_CACHE_DIR=/db", "GRYPE_DB_AUTO_UPDATE=false", "GRYPE_DB_REQUIRE_UPDATE_CHECK=false"]
    _, code = run(container("grype", mounts, args, env=env, database_volume=cache), allowed=(2,))
    report = document(fixture / "result.json")
    require(code == 2 and any(vulnerability.get("id") == "CVE-2021-44228" for item in report["matches"]
                             for vulnerability in [item["vulnerability"]] + item.get("relatedVulnerabilities", [])),
            "dependency vulnerability canary not detected")
    empty = work / "empty-db"
    empty.mkdir()
    try:
        run(container("grype", [(fixture, "/fixture", True), (POLICY / "grype.yaml", "/policy/grype.yaml", False), (empty, "/db", True)],
                      args, env=env))
    except GateError:
        return
    raise GateError("missing database unexpectedly reported as clean")


def apply_exceptions(findings, policy, today):
    require(set(policy) == {"exceptions"} and isinstance(policy["exceptions"], list), "invalid exception policy")
    exceptions = {}
    for entry in policy["exceptions"]:
        require(set(entry) == {"key", "owner", "approvedBy", "reason", "evidence", "reviewedOn", "expiresOn"},
                "exception requires exact identity, owner, approval, evidence and dates")
        require(all(isinstance(value, str) and value.strip() for value in entry.values()), "empty exception field")
        require(entry["key"].startswith(("dependency|", "sast|")) and "*" not in entry["key"], "blanket/secret waivers forbidden")
        try:
            reviewed, expires = dt.date.fromisoformat(entry["reviewedOn"]), dt.date.fromisoformat(entry["expiresOn"])
        except ValueError as error:
            raise GateError("invalid exception dates") from error
        require(reviewed <= today < expires and 0 < (expires - reviewed).days <= 30, "expired/future or overlong exception")
        require(entry["key"] not in exceptions, "duplicate exception")
        exceptions[entry["key"]] = entry
    keys = {finding["key"] for finding in findings}
    require(set(exceptions) <= keys, "unmatched exception; remove stale waiver")
    result = []
    for finding in findings:
        result.append(finding | {"excepted": finding["key"] in exceptions})
    return result


def source_file_sha256_lf(path):
    """Public source-file identity, not a digest of matched secret material."""
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def review_secret_findings(leaks, policy, source, now):
    """Accept only time-bounded, owner-reviewed synthetic source false positives.

    Never alter detector results or export matches. File identity binds all
    contents, not only a line that could contain a newly substituted credential.
    This policy applies only to tracked-source review, never image resources.
    """
    require(isinstance(policy, dict) and set(policy) == {
        "schemaVersion", "gitleaksImage", "gitleaksConfigSha256Lf", "reviews"
    } and type(policy["schemaVersion"]) is int and policy["schemaVersion"] == 1,
            "invalid source secret-review policy")
    require(policy["gitleaksImage"] == TOOLS["gitleaks"]
            and policy["gitleaksConfigSha256Lf"] == source_file_sha256_lf(source / "ops/ci/gitleaks.toml"),
            "source secret review requires unchanged scanner image and configuration")
    require(isinstance(leaks, list) and isinstance(policy["reviews"], list) and len(policy["reviews"]) <= 7,
            "invalid or excessive source secret reviews")
    require(isinstance(now, dt.datetime) and now.tzinfo is not None, "timezone-aware secret-review clock required")
    fields = {"id", "rule", "path", "startLine", "endLine", "occurrences", "sourceSha256Lf",
              "owner", "approvedBy", "reason", "evidence", "reviewedAt", "expiresAt"}
    identities, identifiers, deadlines = {}, set(), []
    for entry in policy["reviews"]:
        require(isinstance(entry, dict) and set(entry) == fields, "incomplete source secret review")
        require(all(isinstance(entry[key], str) and entry[key].strip() for key in fields - {
            "startLine", "endLine", "occurrences"
        }), "empty source secret-review field")
        require(re.fullmatch(r"source-fp-[0-9]{2}", entry["id"]) and entry["id"] not in identifiers,
                "invalid or duplicate source secret-review identifier")
        require(entry["rule"] == "generic-api-key", "only reviewed generic synthetic findings may be accepted")
        relative = Path(entry["path"])
        require(re.fullmatch(r"[A-Za-z0-9_./-]+", entry["path"]) and not relative.is_absolute()
                and all(not part.startswith(".") for part in relative.parts) and relative.as_posix() == entry["path"]
                and (entry["path"].startswith("src/test/") or (
                    entry["path"].startswith("docs/") and entry["path"].endswith(".md")))
                and entry["path"] != "docs/README.md"
                and not re.fullmatch(r"docs/phase-.*\.md", entry["path"]),
                "source secret review must name one test or tracked-document file")
        candidate = source / relative
        require(candidate.is_file() and not candidate.is_symlink()
                and candidate.resolve().is_relative_to(source.resolve())
                and not any((source / Path(*relative.parts[:index])).is_symlink()
                            for index in range(1, len(relative.parts) + 1)),
                "missing, escaping or symlink reviewed source")
        require(re.fullmatch(r"[a-f0-9]{64}", entry["sourceSha256Lf"])
                and source_file_sha256_lf(candidate) == entry["sourceSha256Lf"],
                "reviewed source contents changed; new review required")
        require(type(entry["startLine"]) is int and type(entry["endLine"]) is int
                and 1 <= entry["startLine"] == entry["endLine"] <= len(candidate.read_bytes().splitlines())
                and type(entry["occurrences"]) is int and 1 <= entry["occurrences"] <= 2,
                "exact source review line and bounded occurrence count required")
        reference = entry["evidence"].partition("#")
        require(re.fullmatch(r"docs/[A-Za-z0-9_-]+\.md", reference[0])
                and reference[1] == "#" and re.fullmatch(r"[a-z0-9-]+", reference[2])
                and (source / reference[0]).is_file() and not (source / reference[0]).is_symlink(),
                "source secret review requires tracked review evidence")
        headings = {re.sub(r"[^a-z0-9 -]", "", title.strip().lower()).replace(" ", "-")
                    for title in re.findall(r"^#{1,6}\s+(.+)$", (source / reference[0]).read_text(encoding="utf-8"), re.M)}
        require(reference[2] in headings, "source secret-review evidence anchor missing")
        try:
            reviewed = dt.datetime.fromisoformat(entry["reviewedAt"].replace("Z", "+00:00"))
            expires = dt.datetime.fromisoformat(entry["expiresAt"].replace("Z", "+00:00"))
            require(reviewed.tzinfo is not None and expires.tzinfo is not None,
                    "timezone-aware source review dates required")
            require(reviewed <= now < expires and dt.timedelta(0) < expires - reviewed <= dt.timedelta(days=30),
                    "expired, future or overlong source secret review")
        except ValueError as error:
            raise GateError("invalid source secret-review dates") from error
        identity = (entry["rule"], entry["path"], entry["startLine"], entry["endLine"])
        require(identity not in identities, "duplicate source secret-review location")
        identities[identity] = entry
        identifiers.add(entry["id"])
        deadlines.append(expires)
    require(sum(entry["occurrences"] for entry in identities.values()) <= 10,
            "source secret-review approval count exceeded")
    observed, unreviewed = {}, 0
    for leak in leaks:
        require(isinstance(leak, dict) and isinstance(leak.get("File"), str)
                and isinstance(leak.get("RuleID"), str) and type(leak.get("StartLine")) is int
                and type(leak.get("EndLine")) is int and 1 <= leak["StartLine"] <= leak["EndLine"],
                "malformed source secret finding")
        # No suffix/basename/case folding: only the scanner's exact source root.
        identity = (leak["RuleID"], leak["File"].removeprefix("/src/"), leak["StartLine"], leak["EndLine"])
        if identity in identities:
            observed[identity] = observed.get(identity, 0) + 1
        else:
            unreviewed += 1
    require(set(observed) == set(identities), "stale or unmatched source secret review; remove or re-review")
    require(all(observed[key] == entry["occurrences"] for key, entry in identities.items()),
            "reviewed secret-finding count changed; new review required")
    return {"acceptedSecretFindings": sum(observed.values()), "unreviewedSecretFindings": unreviewed,
            "acceptedSecretReviewIds": sorted(identifiers),
            "acceptedSecretReviewExpiresAt": min(deadlines).isoformat() if deadlines else None}


def require_current_source_reviews(report, now):
    """A fresh scan cannot extend an accepted review's exclusive deadline."""
    counts = [report.get(key) for key in
              ("secretFindings", "acceptedSecretFindings", "unreviewedSecretFindings")]
    require(all(type(value) is int and value >= 0 for value in counts)
            and counts[0] == counts[1] and counts[2] == 0,
            "release blocked: source secret-review accounting is incomplete")
    if not counts[1]:
        return
    try:
        value = report.get("acceptedSecretReviewExpiresAt")
        require(isinstance(value, str), "release blocked: source secret-review expiry missing")
        expires = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(expires.tzinfo is not None and now.tzinfo is not None and now < expires,
                "release blocked: source secret review expired or timezone missing")
    except ValueError as error:
        raise GateError("release blocked: invalid source secret-review expiry") from error


def security(evidence):
    evidence["runtime"] = preflight()
    evidence["tools"] = TOOLS
    with tempfile.TemporaryDirectory(prefix="flowpay-ci-") as directory:
        work = Path(directory)
        evidence["stage"] = "pinned-tool-downloads"
        print("security: downloading pinned tools", flush=True)
        for tool in ("grype", "gitleaks", "semgrep"):
            run(["docker", "pull", TOOLS[tool]], timeout=300)
        evidence["stage"] = "native-source-controls"
        print("security: native source controls", flush=True)
        negative_scanners(work)
        evidence["nativeNegativeAndCleanControls"] = "passed"
        source, output = work / "source", work / "reports"
        source.mkdir()
        output.mkdir()
        evidence["trackedFiles"] = tracked_snapshot(source)
        evidence["trackedSnapshotSha256"] = snapshot_digest(source)
        evidence["stage"] = "tracked-source-scans"
        print("security: tracked source scans", flush=True)
        leaks, sast = source_scans(source, output)
        evidence["secretFindings"] = len(leaks)  # Never include rule match, line text or file contents.
        findings = []
        for item in sast["results"]:
            path = item["path"].removeprefix("/src/")
            findings.append({"key": f"sast|{token(item['check_id'])}|{token(path)}:{int(item['start']['line'])}",
                             "severity": "HIGH", "blocking": True})
        evidence["findings"] = findings
        # Resolve the actual Maven graph, including test and provided dependencies.
        inventory = work / "inventory"
        inventory.mkdir()
        evidence["stage"] = "resolved-maven-sbom"
        print("security: resolving Maven SBOM", flush=True)
        maven([f"org.cyclonedx:cyclonedx-maven-plugin:{TOOLS['cyclonedx']}:makeBom",
               "-DincludeTestScope=true", "-DincludeProvidedScope=true", "-DoutputFormat=json",
               "-DschemaVersion=1.6", "-Dcyclonedx.skipAttach=true", f"-DoutputDirectory={inventory}"])
        bom = document(inventory / "bom.json")
        projected_bom = safe_bom(bom)
        write_json(SAFE / "sbom.cdx.json", projected_bom)
        evidence["sbomComponents"] = len(projected_bom["components"])
        evidence["sbomSha256"] = sha256(SAFE / "sbom.cdx.json")
        # Only the public package inventory and DB directory reach network-enabled Grype.
        evidence["stage"] = "dependency-scan"
        print("security: dependency scan with fresh database", flush=True)
        with scanner_database() as db_cache:
            run(container("grype", [(inventory, "/out", True), (POLICY / "grype.yaml", "/policy/grype.yaml", False)],
                          ["--config", "/policy/grype.yaml", "sbom:/out/bom.json", "--output", "json", "--file", "/out/dependencies.json"],
                          network="bridge", env=["GRYPE_DB_CACHE_DIR=/db"], database_volume=db_cache), timeout=600)
            # This pinned release wraps DB metadata in descriptor.db.status.
            # Cross-check it against the official offline status command reading
            # the same invocation-owned DB, never a fallback/shared cache.
            status, _ = run(container("grype", [(POLICY / "grype.yaml", "/policy/grype.yaml", False)],
                                      ["--config", "/policy/grype.yaml", "db", "status", "--output", "json"],
                                      env=["GRYPE_DB_CACHE_DIR=/db", "GRYPE_DB_AUTO_UPDATE=false",
                                           "GRYPE_DB_REQUIRE_UPDATE_CHECK=false"], database_volume=db_cache))
            deps, database = dependency_findings(document(inventory / "dependencies.json"), json.loads(status),
                                                 dt.datetime.now(dt.timezone.utc))
            evidence["stage"] = "native-dependency-controls"
            print("security: native dependency controls", flush=True)
            dependency_controls(work, db_cache)
        evidence["nativeDependencyAndUnavailableDatabaseControls"] = "passed"
        findings += deps
        evidence["database"] = database
        evidence["findings"] = apply_exceptions(findings, document(POLICY / "exceptions.json"),
                                                dt.datetime.now(dt.timezone.utc).date())
        evidence["stage"] = "severity-policy"
        reviews = review_secret_findings(leaks, document(source / "ops/ci/secret-reviews.json"), source,
                                         dt.datetime.now(dt.timezone.utc))
        evidence.update(reviews)
        require(reviews["unreviewedSecretFindings"] == 0, "unreviewed secret findings block; no automatic baseline waiver")
        require(not any(item["blocking"] and not item["excepted"] for item in evidence["findings"]),
                "unapproved blocking SAST/dependency findings; see safe evidence")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("gate", choices=("verify", "security"))
    args = parser.parse_args()
    output = SAFE / ("verification.json" if args.gate == "verify" else "security.json")
    # A failed new invocation cannot leave a stale successful SBOM/result behind.
    output.unlink(missing_ok=True)
    if args.gate == "security":
        (SAFE / "sbom.cdx.json").unlink(missing_ok=True)
    evidence = {"gate": args.gate, "status": "incomplete", "timestamp": dt.datetime.now(dt.timezone.utc).isoformat()}
    commit, _ = run(["git", "rev-parse", "HEAD"])
    evidence["sourceCommit"] = token(commit.decode().strip())
    changes, _ = run(["git", "status", "--porcelain", "--untracked-files=no"])
    evidence["trackedWorktreeDirty"] = bool(changes)
    try:
        (verify if args.gate == "verify" else security)(evidence)
        evidence["status"] = "passed"
    except GateError as error:
        evidence["status"] = "failed"
        evidence["reason"] = str(error)
    except Exception:
        evidence["status"] = "failed"
        evidence["reason"] = "unexpected gate/report failure; no clean result"
    finally:
        write_json(output, evidence)
    print(f"{args.gate}: {evidence['status']} (safe evidence: {output.relative_to(ROOT)})")
    return 0 if evidence["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
