"""Offline image evidence/policy controls; real image tests run separately."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("image_gate", Path(__file__).resolve().parents[2] / "container/image.py")
image = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(image)
gate = image.gate


class ImageTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.safe = self.root / "target/ci-safe"
        self.safe.mkdir(parents=True)
        self.jar = self.root / "target/backend-test.jar"
        for name in ("pom.xml", "mvnw", "mvnw.cmd"):
            (self.root / name).write_text("fixture")
        self.package()
        self.evidence()
        for name, value in (("ROOT", self.root), ("SAFE", self.safe)):
            context = patch.object(image, name, value)
            context.start()
            self.addCleanup(context.stop)

    def package(self, extra=None):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("BOOT-INF/classes/com/flowpay/backend/FlowPayApplication.class", b"fixture")
            if extra:
                archive.writestr(extra, "fixture")

    def evidence(self, **extra):
        gate.write_json(self.safe / "verification.json", {"status": "passed", "jarSha256": gate.sha256(self.jar),
                        "applicationInputsSha256": gate.application_inputs_digest(self.root), **extra})

    def test_exact_clean_verified_jar_required(self):
        self.assertEqual(image.verified_jar(), self.jar)

    def test_failed_or_missing_verification_rejected(self):
        self.evidence(status="failed")
        with self.assertRaises(gate.GateError):
            image.verified_jar()
        (self.safe / "verification.json").unlink()
        with self.assertRaises(gate.GateError):
            image.verified_jar()

    def test_jar_substitution_rejected(self):
        self.jar.write_bytes(b"changed")
        with self.assertRaises(gate.GateError):
            image.verified_jar()

    def test_changed_build_inputs_rejected(self):
        (self.root / "pom.xml").write_text("changed")
        with self.assertRaises(gate.GateError):
            image.verified_jar()

    def test_added_source_rejected(self):
        source = self.root / "src/main/java/Added.java"
        source.parent.mkdir(parents=True)
        source.write_text("changed")
        with self.assertRaises(gate.GateError):
            image.verified_jar()

    def test_unrelated_local_env_and_doc_changes_are_not_build_inputs(self):
        # Not actual app secrets; the digest must not read local .env at all.
        (self.root / ".env").write_text("local-only-fixture")
        (self.root / "README.md").write_text("documentation")
        self.assertEqual(image.verified_jar(), self.jar)

    def test_multiple_artifacts_rejected(self):
        (self.jar.parent / "backend-other.jar").write_bytes(b"fixture")
        with self.assertRaises(gate.GateError):
            image.verified_jar()

    def test_source_or_keys_in_application_rejected(self):
        for name in ("BOOT-INF/classes/.env", "BOOT-INF/classes/key.pem", "BOOT-INF/classes/private.key",
                     "BOOT-INF/classes/store.p12", "src/App.java", "BOOT-INF/classes/com/flowpay/backend/testing/Fixture.class"):
            with self.subTest(name=name):
                self.package(name)
                self.evidence()
                with self.assertRaises(gate.GateError):
                    image.verified_jar()

    def test_non_executable_jar_rejected(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("other", "fixture")
        self.evidence()
        with self.assertRaises(gate.GateError):
            image.verified_jar()

    def bom(self):
        return {"bomFormat": "CycloneDX", "specVersion": "1.6", "components": [
            {"type": "library", "name": "web", "version": "1", "purl": "pkg:maven/example/web@1",
             "properties": [{"name": "secret", "value": "MUST_NOT_EXPORT"}]},
            {"type": "library", "name": "libc", "version": "1", "purl": "pkg:deb/ubuntu/libc@1"}],
            "metadata": {"path": "MUST_NOT_EXPORT"}}

    def test_image_sbom_includes_os_and_java_only_safe_fields(self):
        projected = image.safe_image_bom(self.bom())
        self.assertEqual(len(projected["components"]), 2)
        self.assertNotIn("MUST_NOT_EXPORT", json.dumps(projected))

    def test_file_metadata_stays_private_without_dropping_package_inventory(self):
        bom = self.bom()
        bom["components"].append({"type": "file", "name": "/etc/ssl/certs/fixture-\u0151.pem", "bom-ref": "private-file"})
        projected = image.safe_image_bom(bom)
        self.assertEqual(len(projected["components"]), 2)
        self.assertNotIn("private-file", json.dumps(projected))

    def test_partial_empty_or_malformed_inventory_rejected(self):
        for components in ([], self.bom()["components"][:1], self.bom()["components"][1:]):
            with self.subTest(components=components), self.assertRaises(gate.GateError):
                image.safe_image_bom({**self.bom(), "components": components})

    def test_untrusted_inventory_text_rejected(self):
        bom = self.bom()
        bom["components"][0]["name"] = "unsafe text\n"
        with self.assertRaises(gate.GateError):
            image.safe_image_bom(bom)

    def test_image_identity_cannot_use_failed_or_missing_build(self):
        gate.write_json(self.safe / "image-build.json", {"status": "failed"})
        with self.assertRaises(gate.GateError):
            image.image_identity()

    def test_image_identity_rejects_changed_dockerfile(self):
        (self.root / "Dockerfile").write_text("reviewed")
        gate.write_json(self.safe / "image-build.json", {"status": "passed", "jarSha256": gate.sha256(self.jar),
                        "dockerfileSha256": gate.sha256(self.root / "Dockerfile"), "imageId": "sha256:" + "a" * 64})
        self.assertTrue(image.image_identity().startswith("sha256:"))
        (self.root / "Dockerfile").write_text("changed")
        with self.assertRaises(gate.GateError):
            image.image_identity()

    def test_repository_context_is_deny_all(self):
        policy = (Path(__file__).resolve().parents[3] / ".dockerignore").read_text()
        entries = [line for line in policy.splitlines() if line and not line.startswith("#")]
        self.assertEqual(entries, ["**", "!Dockerfile"])

    def test_no_registry_writes_or_dependency_waivers(self):
        source = (Path(__file__).resolve().parents[2] / "container/image.py").read_text()
        self.assertNotIn('"push"', source)
        self.assertNotIn("apply_exceptions(", source)

    def test_restart_only_exact_owned_container_not_compose_dependencies(self):
        state = {"Id": "a" * 64, "Config": {"Labels": {"com.docker.compose.project": "owned-project"}}}
        with patch.object(gate, "run") as invoked:
            image.start_owned_container("owned-project", state)
        self.assertEqual(invoked.call_args.args[0], ["docker", "start", "a" * 64])

    def test_restart_cannot_touch_foreign_project(self):
        state = {"Id": "a" * 64, "Config": {"Labels": {"com.docker.compose.project": "foreign-project"}}}
        with patch.object(gate, "run") as invoked, self.assertRaises(gate.GateError):
            image.start_owned_container("owned-project", state)
        invoked.assert_not_called()

    def test_network_scanner_mount_contains_only_inventory(self):
        folder = self.root / "inventory"
        folder.mkdir()
        (folder / "image.cdx.json").write_text("{}")
        mounts = image.vulnerability_mounts(folder)
        self.assertEqual(mounts[0], (folder, "/out", True))
        self.assertEqual(mounts[1][1:], ("/policy/grype.yaml", False))

    def test_network_scanner_cannot_receive_archive_resources_or_secret_reports(self):
        folder = self.root / "inventory"
        folder.mkdir()
        (folder / "image.cdx.json").write_text("{}")
        for name in ("image.tar", "jar-content", "secrets.json"):
            with self.subTest(name=name):
                extra = folder / name
                extra.write_text("private-fixture")
                with self.assertRaises(gate.GateError):
                    image.vulnerability_mounts(folder)
                extra.unlink()
