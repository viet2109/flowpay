"""No real secrets or Docker required; native scanner controls run in gate security."""
import datetime as dt
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("gate", Path(__file__).resolve().parents[1] / "gate.py")
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class GateTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)

    def report(self, *, skipped=0, failures=0, errors=0, tests=1, child="", name="sample.RequiredTest"):
        source = self.root / "src/test/java/sample/RequiredTest.java"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("package sample; class RequiredTest {}")
        reports = self.root / "target/surefire-reports"
        reports.mkdir(parents=True, exist_ok=True)
        path = reports / "TEST-sample.RequiredTest.xml"
        path.write_text(f'<testsuite name="{name}" tests="{tests}" skipped="{skipped}" '
                        f'failures="{failures}" errors="{errors}">{child}</testsuite>')
        return path

    def test_reports_pass_without_publishing_system_output(self):
        self.report(child="<system-out>SENSITIVE_TEST_ONLY</system-out>")
        result = gate.verify_reports(self.root, time.time())
        self.assertEqual(result["tests"], 1)
        self.assertNotIn("SENSITIVE", json.dumps(result))

    def test_skipped_infrastructure_cannot_pass(self):
        self.report(skipped=1)
        with self.assertRaises(gate.GateError):
            gate.verify_reports(self.root, time.time())

    def test_skipped_testcase_cannot_hide_behind_zero_counter(self):
        self.report(child="<testcase><skipped/></testcase>")
        with self.assertRaises(gate.GateError):
            gate.verify_reports(self.root, time.time())

    def test_failure_error_and_empty_suite_fail(self):
        for counters in ({"failures": 1}, {"errors": 1}, {"tests": 0}):
            with self.subTest(counters=counters):
                self.report(**counters)
                with self.assertRaises(gate.GateError):
                    gate.verify_reports(self.root, time.time())

    def test_missing_suite_fails_even_with_other_successful_reports(self):
        self.report(name="sample.OtherTest")
        with self.assertRaises(gate.GateError):
            gate.verify_reports(self.root, time.time())

    def test_stale_and_malformed_reports_fail(self):
        path = self.report()
        with self.assertRaises(gate.GateError):
            gate.verify_reports(self.root, time.time() + 10)
        path.write_text("not XML")
        with self.assertRaises(gate.GateError):
            gate.verify_reports(self.root, time.time())

    def test_new_test_sources_are_automatically_required(self):
        self.report()
        (self.root / "src/test/java/sample/NewIntegrationTest.java").write_text("package sample; class NewIntegrationTest {}")
        with self.assertRaises(gate.GateError):
            gate.verify_reports(self.root, time.time())

    def test_abstract_test_support_is_not_expected_to_execute(self):
        self.report()
        (self.root / "src/test/java/sample/PostgresIntegrationTest.java").write_text(
            "package sample; public abstract class PostgresIntegrationTest {}")
        self.assertEqual(gate.verify_reports(self.root, time.time())["requiredSourceSuites"], 1)

    def test_missing_and_windows_docker_fail(self):
        for output in (b"", b"windows"):
            with self.subTest(output=output), patch.object(gate, "run", return_value=(output, 0)):
                with self.assertRaises(gate.GateError):
                    gate.preflight()
        with patch.object(gate.subprocess, "run", side_effect=FileNotFoundError("PRIVATE")):
            with self.assertRaisesRegex(gate.GateError, "command unavailable"):
                gate.run(["not-installed"])

    def test_maven_java_home_mismatch_fails_even_when_path_java_is_21(self):
        java = subprocess.CompletedProcess(["java"], 0, stdout=b"", stderr=b'openjdk version "21.0.12"')
        with patch.object(gate, "run", return_value=(b"linux", 0)), patch.object(gate.subprocess, "run", return_value=java):
            with patch.object(gate, "maven", return_value=(b"Java version: 17.0.10, vendor: test", 0)):
                with self.assertRaisesRegex(gate.GateError, "Maven must actually run on JDK 21"):
                    gate.preflight()
            with patch.object(gate, "maven", return_value=(b"Java version: 21.0.12, vendor: test", 0)):
                self.assertEqual(gate.preflight()["mavenJavaVersion"], "21.0.12")

    def test_scanner_failure_and_timeout_are_not_clean_or_echoed(self):
        result = subprocess.CompletedProcess(["scanner"], 9)
        with patch.object(gate.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(gate.GateError, "raw output withheld"):
                gate.run(["scanner"])

    def test_ci_failure_annotation_exports_fixed_codes_not_private_error_text(self):
        with patch.dict(gate.os.environ, {"GITHUB_ACTIONS": "true"}), patch("builtins.print") as printed:
            gate.publish_failure_annotation(dict(status="failed", stage="dependency-scan",
                                                reason="command failed; raw output withheld"))
            printed.assert_called_once_with("::error title=FlowPay quality gate::stage=dependency-scan; code=subprocess-failed")
        with patch.dict(gate.os.environ, {"GITHUB_ACTIONS": "true"}), patch("builtins.print") as printed:
            gate.publish_failure_annotation(dict(status="failed", stage="private-marker\n::warning::injection",
                                                reason="private-marker", payload="private-marker"))
            printed.assert_called_once_with("::error title=FlowPay quality gate::stage=preflight-or-other; code=gate-rejected")

    def test_failure_annotation_does_not_emit_for_pass_or_non_ci(self):
        for environment, status in (("false", "failed"), ("true", "passed")):
            with patch.dict(gate.os.environ, {"GITHUB_ACTIONS": environment}), patch("builtins.print") as printed:
                gate.publish_failure_annotation(dict(status=status, stage="dependency-scan"))
            printed.assert_not_called()

    def test_timeout_cleanup_requires_owned_name_and_label(self):
        name = "flowpay-ci-" + "a" * 32
        with patch.object(gate.subprocess, "run") as invoked:
            gate.cleanup_container(["docker", "run", "--name", "unrelated"])
            invoked.assert_not_called()
        for label, expected_calls in ((b"somebody-else", 1), (name.encode(), 2)):
            inspected = subprocess.CompletedProcess(["docker"], 0, stdout=label, stderr=b"")
            with patch.object(gate.subprocess, "run", return_value=inspected) as invoked:
                gate.cleanup_container(["docker", "run", "--name", name])
                self.assertEqual(invoked.call_count, expected_calls)
                if expected_calls == 2:
                    self.assertEqual(invoked.call_args.args[0], ["docker", "rm", "--force", name])

    def test_snapshot_digest_changes_with_source(self):
        file = self.root / "source.txt"
        file.write_text("before")
        before = gate.snapshot_digest(self.root)
        file.write_text("after")
        self.assertNotEqual(gate.snapshot_digest(self.root), before)

    def test_database_volume_cleanup_checks_ownership(self):
        def responses(command, **kwargs):
            if command[:3] == ["docker", "volume", "inspect"]:
                return b"not-owned", 0
            return b"", 0
        with patch.object(gate, "run", side_effect=responses) as invoked:
            with self.assertRaisesRegex(gate.GateError, "ownership mismatch"):
                with gate.scanner_database() as volume:
                    self.assertRegex(volume, r"flowpay-ci-[a-f0-9]{32}")
            self.assertFalse(any(call.args[0][:3] == ["docker", "volume", "rm"] for call in invoked.call_args_list))

    def test_verify_orchestration_uses_clean_and_checks_fresh_reports(self):
        for name in ("pom.xml", "mvnw", "mvnw.cmd"):
            (self.root / name).write_text("build-input-fixture")
        (self.root / "target").mkdir()
        jar = self.root / "target/backend-test.jar"
        jar.write_bytes(b"test-only")
        evidence = {}
        with patch.object(gate, "ROOT", self.root), patch.object(gate, "preflight", return_value={"mavenJavaVersion": "21.0.12"}), \
                patch.object(gate, "lint_workflow"), patch.object(gate, "maven") as invoked, \
                patch.object(gate, "verify_reports", return_value={"tests": 1}) as reports:
            gate.verify(evidence)
        self.assertEqual(invoked.call_args.args[0], ["clean", "verify"])
        self.assertGreater(reports.call_args.args[1], time.time() - 5)
        self.assertEqual(evidence["jarSha256"], gate.sha256(jar))
        self.assertEqual(evidence["applicationInputsSha256"], gate.application_inputs_digest(self.root))

    def test_main_replaces_stale_pass_with_safe_failure_evidence(self):
        safe = self.root / "safe"
        safe.mkdir()
        (safe / "security.json").write_text('{"status":"passed"}')
        (safe / "sbom.cdx.json").write_text("old inventory")
        with patch.object(gate, "SAFE", safe), patch.object(gate, "ROOT", self.root), \
                patch.object(gate, "run", return_value=(b"a" * 40, 0)), \
                patch.object(gate, "security", side_effect=gate.GateError("scanner unavailable")), \
                patch.object(gate.sys, "argv", ["gate.py", "security"]), patch("builtins.print"):
            self.assertEqual(gate.main(), 1)
        self.assertFalse((safe / "sbom.cdx.json").exists())
        self.assertEqual(json.loads((safe / "security.json").read_text())["status"], "failed")
        with patch.object(gate.subprocess, "run", side_effect=subprocess.TimeoutExpired("PRIVATE", 1)):
            with self.assertRaisesRegex(gate.GateError, "timed out"):
                gate.run(["scanner"])

    def test_missing_malformed_or_empty_reports_are_not_clean(self):
        path = self.root / "report.json"
        with self.assertRaises(gate.GateError):
            gate.document(path)
        path.write_text("private not JSON")
        with self.assertRaises(gate.GateError):
            gate.document(path)
        with self.assertRaises(gate.GateError):
            gate.safe_bom({"bomFormat": "CycloneDX", "components": []})

    def test_container_is_digest_pinned_and_never_receives_host_credentials(self):
        command = gate.container("semgrep", [], ["--version"])
        self.assertIn("none", command)
        self.assertIn("--read-only", command)
        self.assertNotIn("/var/run/docker.sock", " ".join(command))
        self.assertNotIn("GITHUB_TOKEN", " ".join(command))
        with patch.dict(gate.TOOLS, {"semgrep": "semgrep/semgrep:latest"}):
            with self.assertRaises(gate.GateError):
                gate.container("semgrep", [], [])

    def test_linux_scanners_use_host_uid_without_privilege_bypass(self):
        with patch.object(gate, "scanner_identity", return_value=(1001, 1002)):
            command = gate.container("gitleaks", [], ["--version"])
        self.assertEqual(command[command.index("--user") + 1], "1001:1002")
        self.assertNotIn("--cap-add", command)
        self.assertNotIn("--privileged", command)
        self.assertIn("--read-only", command)
        self.assertEqual(command[command.index("--cap-drop") + 1], "ALL")
        self.assertEqual(command[command.index("--tmpfs") + 1],
                         "/tmp:rw,nosuid,nodev,size=512m,mode=1777")
        with patch.object(gate, "scanner_identity", return_value=None):
            self.assertNotIn("--user", gate.container("gitleaks", [], ["--version"]))

    def test_cache_initializer_is_owned_empty_volume_only_and_not_a_scanner_privilege(self):
        name = "flowpay-ci-" + "b" * 32
        with patch.object(gate, "run", return_value=(name.encode(), 0)) as invoked:
            gate.initialize_scanner_database(name, (1001, 1001))
        command = invoked.call_args.args[0]
        self.assertEqual(command[command.index("--network") + 1], "none")
        self.assertEqual(command[command.index("--user") + 1], "0:0")
        self.assertEqual(command[command.index("--cap-add") + 1], "CHOWN")
        self.assertEqual(command.count("--mount"), 1)
        self.assertIn("type=volume,source=" + name + ",target=/db", command)
        self.assertIn("--read-only", command)
        self.assertIn("not os.listdir(p)", command[-1])
        self.assertNotIn("rglob", command[-1])
        self.assertNotIn("/var/run/docker.sock", " ".join(command))

    def test_cache_initializer_refuses_foreign_volume_or_invalid_owner(self):
        name = "flowpay-ci-" + "b" * 32
        with patch.object(gate, "run") as invoked, self.assertRaises(gate.GateError):
            gate.initialize_scanner_database("development", (1001, 1001))
        invoked.assert_not_called()
        with patch.object(gate, "run", return_value=(b"foreign", 0)) as invoked, self.assertRaises(gate.GateError):
            gate.initialize_scanner_database(name, (1001, 1001))
        self.assertEqual(invoked.call_count, 1)
        for owner in ((-1, 1), (True, 1), (1, "1")):
            with self.subTest(owner=owner), patch.object(gate, "run", return_value=(name.encode(), 0)) as invoked, self.assertRaises(gate.GateError):
                gate.initialize_scanner_database(name, owner)
            self.assertEqual(invoked.call_count, 1)

    def test_linux_cache_initialization_precedes_use_and_failed_init_still_cleans_owned_volume(self):
        def responses(command, **kwargs):
            if command[:3] == ["docker", "volume", "inspect"]:
                return command[-1].encode(), 0
            return b"", 0
        with patch.object(gate, "scanner_identity", return_value=(1001, 1001)), \
                patch.object(gate, "run", side_effect=responses) as invoked, \
                patch.object(gate, "initialize_scanner_database", side_effect=gate.GateError("init failed")), \
                self.assertRaises(gate.GateError):
            with gate.scanner_database():
                self.fail("failed cache initialization must not yield")
        self.assertTrue(any(call.args[0][:3] == ["docker", "volume", "rm"] for call in invoked.call_args_list))

    def test_tracked_snapshot_excludes_untracked_env_and_analysis(self):
        (self.root / "safe.txt").write_text("safe")
        (self.root / ".env").write_text("PRIVATE_TEST_ONLY")
        output = self.root / "snapshot"
        with patch.object(gate, "ROOT", self.root), patch.object(gate, "run", return_value=(b"safe.txt\0", 0)):
            self.assertEqual(gate.tracked_snapshot(output), 1)
        self.assertFalse((output / ".env").exists())

    def test_canonical_security_snapshot_normalizes_git_text_not_binary_or_live_files(self):
        text = self.root / "sample.java"
        original = b"one\r\ntwo\nthree\r\n"
        text.write_bytes(original)
        binary = self.root / "binary.bin"
        binary.write_bytes(b"\0binary\r\n")
        output = self.root / "snapshot"
        replies = [(b"sample.java\0binary.bin\0", 0),
                   (b"i/lf w/mixed attr/\tsample.java\0i/-text w/-text attr/\tbinary.bin\0", 0)]
        with patch.object(gate, "ROOT", self.root), patch.object(gate, "run", side_effect=replies):
            self.assertEqual(gate.tracked_snapshot(output, canonical_text=True), 2)
        self.assertEqual((output / "sample.java").read_bytes(), b"one\ntwo\nthree\n")
        self.assertEqual((output / "binary.bin").read_bytes(), binary.read_bytes())
        self.assertEqual(text.read_bytes(), original)

    def test_canonical_snapshot_refuses_incomplete_or_unexpected_text_inventory(self):
        (self.root / "sample.java").write_bytes(b"one\r\n")
        for metadata in (b"", b"invalid\0", b"i/lf w/unknown attr/\tsample.java\0",
                         b"i/lf w/lf attr/\t../escape\0"):
            with self.subTest(metadata=metadata), patch.object(gate, "ROOT", self.root), \
                    patch.object(gate, "run", side_effect=[(b"sample.java\0", 0), (metadata, 0)]), \
                    self.assertRaises(gate.GateError):
                gate.tracked_snapshot(self.root / "out", canonical_text=True)

    def test_canonical_snapshot_checks_protected_paths_before_eol_inspection(self):
        with patch.object(gate, "run", return_value=(b".env\0", 0)) as invoked, self.assertRaises(gate.GateError):
            gate.tracked_snapshot(self.root / "out", canonical_text=True)
        self.assertEqual(invoked.call_count, 1)

    def test_tracked_local_only_and_symlink_escape_fail(self):
        for name in (".env", "AGENTS.md", "nested/AGENTS.override.md", "docs/README.md", "docs/phase-8-test.md", "../escape"):
            with self.subTest(name=name), patch.object(gate, "run", return_value=(name.encode() + b"\0", 0)):
                with self.assertRaises(gate.GateError):
                    gate.tracked_snapshot(self.root / "snapshot")

    def test_sbom_safe_projection_removes_paths_authors_and_properties(self):
        component = {"type": "library", "name": "example", "version": "1", "purl": "pkg:maven/org/example@1",
                     "bom-ref": "pkg:maven/org/example@1", "properties": [{"value": "PRIVATE"}]}
        bom = {"bomFormat": "CycloneDX", "specVersion": "1.6", "components": [component],
               "metadata": {"component": component, "authors": [{"name": "PRIVATE"}]}}
        projected = gate.safe_bom(bom)
        self.assertNotIn("PRIVATE", json.dumps(projected))
        self.assertEqual(len(projected["components"]), 1)

    def test_dependency_negative_fixture_and_db_freshness(self):
        now = dt.datetime(2026, 10, 15, tzinfo=dt.timezone.utc)
        report = {"matches": [{"vulnerability": {"id": "CVE-2099-0001", "severity": "High"},
                               "artifact": {"purl": "pkg:maven/example/test-only@1"}}],
                  "descriptor": {}}
        database = {"built": now.isoformat(), "schemaVersion": "6.1.3", "valid": True}
        report["descriptor"] = {"db": {"status": database}}
        findings, _ = gate.dependency_findings(report, database, now)
        self.assertTrue(findings[0]["blocking"])
        for delta in (dt.timedelta(hours=73), dt.timedelta(hours=-1)):
            with self.assertRaises(gate.GateError):
                gate.dependency_findings(report, database, now + delta)
        database["error"] = "PRIVATE"
        with self.assertRaises(gate.GateError):
            gate.dependency_findings(report, database, now)

    def test_unknown_severity_blocks_and_schema_drift_fails(self):
        now = dt.datetime.now(dt.timezone.utc)
        report = {"matches": [{"vulnerability": {"id": "CVE-2099-0001", "severity": "Unknown"},
                               "artifact": {"purl": "pkg:maven/example/test-only@1"}}],
                  "descriptor": {}}
        database = {"built": now.isoformat(), "schemaVersion": "6.1.3", "valid": True}
        report["descriptor"] = {"db": {"status": database}}
        self.assertTrue(gate.dependency_findings(report, database, now)[0][0]["blocking"])
        report["matches"][0]["vulnerability"]["severity"] = "unrecognized"
        with self.assertRaises(gate.GateError):
            gate.dependency_findings(report, database, now)

    def test_missing_invalid_or_builtless_database_status_fails(self):
        now = dt.datetime.now(dt.timezone.utc)
        for database in ({}, {"valid": False}, {"valid": True}, {"valid": True, "built": "bad-date"}):
            with self.subTest(database=database), self.assertRaises(gate.GateError):
                gate.dependency_findings({"matches": []}, database, now)

    def test_report_database_identity_must_match_offline_status(self):
        now = dt.datetime.now(dt.timezone.utc)
        database = {"built": now.isoformat(), "schemaVersion": "6.1.3", "valid": True}
        for reported in ({}, database | {"schemaVersion": "6.0.0"}, database | {"valid": False},
                         database | {"built": "2026-10-14T00:00:00Z"}):
            report = {"matches": [], "descriptor": {"db": {"status": reported}}}
            with self.subTest(reported=reported), self.assertRaises(gate.GateError):
                gate.dependency_findings(report, database, now)

    def exception(self):
        return {"key": "dependency|CVE-2099-0001|pkg:maven/example/test-only@1", "owner": "maintainer",
                "approvedBy": "reviewer", "reason": "synthetic test, not a real waiver", "evidence": "test-only",
                "reviewedOn": "2026-10-15", "expiresOn": "2026-10-22"}

    def test_exact_reviewed_unexpired_exception_only(self):
        entry = self.exception()
        findings = [{"key": entry["key"], "blocking": True}]
        result = gate.apply_exceptions(findings, {"exceptions": [entry]}, dt.date(2026, 10, 15))
        self.assertTrue(result[0]["excepted"])
        self.assertFalse(gate.apply_exceptions(findings, {"exceptions": []}, dt.date(2026, 10, 15))[0]["excepted"])

    def test_expired_long_blanket_secret_unapproved_stale_exceptions_fail(self):
        base = self.exception()
        for change in ({"expiresOn": "2026-10-15"}, {"expiresOn": "2026-12-01"}, {"approvedBy": ""},
                       {"key": "dependency|*"}, {"key": "secret|test"}, {"key": "dependency|not-found"},
                       {"reviewedOn": "2026-10-16"}):
            with self.subTest(change=change):
                with self.assertRaises(gate.GateError):
                    gate.apply_exceptions([{"key": base["key"]}], {"exceptions": [base | change]}, dt.date(2026, 10, 15))

    def test_workflow_security_contract(self):
        source = (gate.ROOT / ".github/workflows/ci.yml").read_text()
        actions = re.findall(r"uses:\s*([^\s]+)", source)
        self.assertEqual(len(actions), 6)
        self.assertTrue(all(re.fullmatch(r"actions/[\w-]+@[a-f0-9]{40}", action) for action in actions))
        self.assertIn("contents: read", source)
        for forbidden in ("pull_request_target:", "secrets.", "write-all", "continue-on-error", "target/**", "cache: maven"):
            self.assertNotIn(forbidden, source)
        self.assertEqual(source.count("persist-credentials: false"), 2)
        self.assertIn("ops/ci/gate.py verify", source)
        self.assertIn("ops/ci/gate.py security", source)


if __name__ == "__main__":
    unittest.main()
