"""Exact source false-positive acceptance; no credentials, Docker or waivers."""
import copy
import datetime as dt
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("secret_review_gate", Path(__file__).resolve().parents[1] / "gate.py")
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class SecretReviewTest(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.source = Path(work.name)
        self.relative = "src/test/java/FixtureTest.java"
        self.fixture = self.source / self.relative
        self.fixture.parent.mkdir(parents=True)
        self.fixture.write_bytes(b"reviewed synthetic fixture\nother source line\n")
        config = self.source / "ops/ci/gitleaks.toml"
        config.parent.mkdir(parents=True)
        config.write_bytes(b"fixture-only detector configuration\n")
        review = self.source / "docs/review.md"
        review.parent.mkdir()
        review.write_text("# Approval\nSynthetic policy test, not owner authorization.\n")
        self.now = dt.datetime(2026, 10, 18, 12, tzinfo=dt.timezone.utc)
        self.entry = {
            "id": "source-fp-01", "rule": "generic-api-key", "path": self.relative,
            "startLine": 1, "endLine": 1, "occurrences": 1,
            "sourceSha256Lf": gate.source_file_sha256_lf(self.fixture),
            "owner": "test-maintainer", "approvedBy": "synthetic-reviewer",
            "reason": "Unit-test policy only", "evidence": "docs/review.md#approval",
            "reviewedAt": (self.now - dt.timedelta(days=1)).isoformat(),
            "expiresAt": (self.now + dt.timedelta(days=29)).isoformat(),
        }
        self.policy = {"schemaVersion": 1, "gitleaksImage": gate.TOOLS["gitleaks"],
                       "gitleaksConfigSha256Lf": gate.source_file_sha256_lf(config), "reviews": [self.entry]}
        self.finding = {"RuleID": "generic-api-key", "File": "/src/" + self.relative,
                        "StartLine": 1, "EndLine": 1, "Secret": "PRIVATE_LITERAL_MARKER"}

    def check(self, leaks=None, policy=None, now=None):
        return gate.review_secret_findings([self.finding] if leaks is None else leaks,
                                           self.policy if policy is None else policy, self.source,
                                           self.now if now is None else now)

    def test_exact_current_review_accepted_without_exporting_match(self):
        result = self.check()
        self.assertEqual(result, {"acceptedSecretFindings": 1, "unreviewedSecretFindings": 0,
                                  "acceptedSecretReviewIds": ["source-fp-01"],
                                  "acceptedSecretReviewExpiresAt": self.entry["expiresAt"]})
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertNotIn(self.entry["sourceSha256Lf"], json.dumps(result))

    def test_empty_policy_does_not_accept_any_secret(self):
        self.assertEqual(self.check(policy=self.policy | {"reviews": []})["unreviewedSecretFindings"], 1)
        self.assertEqual(self.check(leaks=[], policy=self.policy | {"reviews": []})["acceptedSecretFindings"], 0)

    def test_approved_exact_lf_spans_keep_both_rows_at_one_source_location(self):
        entry = self.entry | {'occurrences': 2, 'findingSpans': [
            {'startLine': 1, 'endLine': 1, 'occurrences': 1},
            {'startLine': 1, 'endLine': 2, 'occurrences': 1}]}
        result = self.check(leaks=[self.finding, self.finding | {'EndLine': 2}],
                            policy=self.policy | {'reviews': [entry]})
        self.assertEqual(result['acceptedSecretFindings'], 2)
        self.assertEqual(result['acceptedSecretReviewIds'], ['source-fp-01'])
        self.assertEqual(result['acceptedSecretReviewExpiresAt'], entry['expiresAt'])

    def test_explicit_spans_reject_missing_duplicate_shifted_or_extra_rows(self):
        entry = self.entry | {'occurrences': 2, 'findingSpans': [
            {'startLine': 1, 'endLine': 1, 'occurrences': 1},
            {'startLine': 1, 'endLine': 2, 'occurrences': 1}]}
        for leaks in ([self.finding], [self.finding, self.finding],
                      [self.finding | {'StartLine': 2, 'EndLine': 2}],
                      [self.finding, self.finding | {'EndLine': 2}, self.finding]):
            with self.subTest(leaks=leaks), self.assertRaises(gate.GateError):
                self.check(leaks=leaks, policy=self.policy | {'reviews': [entry]})

    def test_multiline_span_is_not_accepted_without_explicit_approval(self):
        with self.assertRaises(gate.GateError):
            self.check(leaks=[self.finding | {'EndLine': 2}])

    def test_span_schema_is_bounded_and_cannot_be_used_as_a_fuzzy_range(self):
        spans = [{'startLine': 1, 'endLine': 1, 'occurrences': 1},
                 {'startLine': 1, 'endLine': 2, 'occurrences': 1}]
        for value in (None, [], spans[:1], spans + spans, [spans[0], spans[0]],
                      [spans[0], spans[1] | {'EndLine': 2}],
                      [spans[0], spans[1] | {'startLine': True}],
                      [spans[0], spans[1] | {'endLine': 3}],
                      [spans[0], spans[1] | {'occurrences': 2}]):
            with self.subTest(spans=value), self.assertRaises(gate.GateError):
                self.check(policy=self.policy | {'reviews': [self.entry | {'occurrences': 2, 'findingSpans': value}]})

    def test_report_deadline_is_earliest_accepted_review_not_latest(self):
        policy = copy.deepcopy(self.policy)
        early = policy['reviews'][0] | {'id': 'source-fp-02', 'startLine': 2, 'endLine': 2,
                                       'expiresAt': (self.now + dt.timedelta(days=1)).isoformat()}
        policy['reviews'].append(early)
        findings = [self.finding, self.finding | {'StartLine': 2, 'EndLine': 2}]
        self.assertEqual(self.check(leaks=findings, policy=policy)['acceptedSecretReviewExpiresAt'], early['expiresAt'])
        self.assertIsNone(self.check(leaks=[], policy=self.policy | {'reviews': []})['acceptedSecretReviewExpiresAt'])

    def test_release_cannot_extend_exclusive_expiry_with_fresh_report(self):
        report = self.check() | {'secretFindings': 1, 'timestamp': self.now.isoformat(), 'status': 'passed'}
        expiry = dt.datetime.fromisoformat(report['acceptedSecretReviewExpiresAt'])
        gate.require_current_source_reviews(report, expiry - dt.timedelta(microseconds=1))
        for now in (expiry, expiry + dt.timedelta(microseconds=1)):
            with self.subTest(now=now), self.assertRaises(gate.GateError):
                gate.require_current_source_reviews(report | {'timestamp': now.isoformat()}, now)

    def test_release_rejects_missing_malformed_or_naive_review_expiry(self):
        report = self.check() | {'secretFindings': 1}
        for value in (None, '', 'not-a-date', 42, '2026-11-17T00:00:00'):
            with self.subTest(value=value), self.assertRaises(gate.GateError):
                gate.require_current_source_reviews(report | {'acceptedSecretReviewExpiresAt': value}, self.now)

    def test_release_requires_complete_review_accounting_but_not_waiver_for_zero_findings(self):
        report = self.check() | {'secretFindings': 1}
        for change in ({'acceptedSecretFindings': 0}, {'unreviewedSecretFindings': 1},
                       {'secretFindings': True}, {'secretFindings': -1}, {'acceptedSecretFindings': '1'}):
            with self.subTest(change=change), self.assertRaises(gate.GateError):
                gate.require_current_source_reviews(report | change, self.now)
        for incomplete in ({}, {'secretFindings': 0}, {'secretFindings': 0, 'acceptedSecretFindings': 0}):
            with self.subTest(incomplete=incomplete), self.assertRaises(gate.GateError):
                gate.require_current_source_reviews(incomplete, self.now)
        gate.require_current_source_reviews({'secretFindings': 0, 'acceptedSecretFindings': 0,
                                            'unreviewedSecretFindings': 0}, self.now)

    def test_new_path_line_or_rule_remains_blocking(self):
        for extra in ({"File": "/src/src/test/java/NewTest.java"}, {"StartLine": 2, "EndLine": 2},
                      {"RuleID": "aws-access-token"}, {"File": "/elsewhere/" + self.relative},
                      {"File": "/src/" + self.relative.upper()}):
            with self.subTest(extra=extra):
                result = self.check(leaks=[self.finding, self.finding | extra])
                self.assertEqual(result["acceptedSecretFindings"], 1)
                self.assertEqual(result["unreviewedSecretFindings"], 1)

    def test_changed_secret_same_location_cannot_reuse_review(self):
        self.fixture.write_text("changed credential at the very same location\nother source line\n")
        with self.assertRaisesRegex(gate.GateError, "contents changed") as result:
            self.check()
        self.assertNotIn("credential at", str(result.exception))

    def test_any_changed_content_in_approved_file_requires_review(self):
        self.fixture.write_bytes(self.fixture.read_bytes() + b"new secret elsewhere\n")
        with self.assertRaisesRegex(gate.GateError, "contents changed"):
            self.check()

    def test_lf_and_crlf_checkouts_have_same_review_identity(self):
        self.fixture.write_bytes(self.fixture.read_bytes().replace(b"\n", b"\r\n"))
        self.assertEqual(self.check()["acceptedSecretFindings"], 1)

    def test_missing_and_unmatched_reviews_fail_closed(self):
        with self.assertRaisesRegex(gate.GateError, "stale or unmatched"):
            self.check(leaks=[])
        self.fixture.unlink()
        with self.assertRaises(gate.GateError):
            self.check()

    def test_duplicate_policy_id_or_location_rejected(self):
        for second in (self.entry.copy(), self.entry | {"id": "source-fp-02"},
                       self.entry | {"startLine": 2, "endLine": 2}):
            with self.subTest(second=second), self.assertRaises(gate.GateError):
                self.check(policy=self.policy | {"reviews": [self.entry, second]})

    def test_exact_report_multiplicity_required_not_deduplicated(self):
        self.entry["occurrences"] = 2
        self.assertEqual(self.check(leaks=[self.finding] * 2)["acceptedSecretFindings"], 2)
        for count in (1, 3):
            with self.subTest(count=count), self.assertRaises(gate.GateError):
                self.check(leaks=[self.finding] * count)

    def test_rule_disablement_path_globs_main_sources_and_local_files_forbidden(self):
        for change in ({"rule": "*"}, {"rule": "aws-access-token"}, {"path": "src/test/**"},
                       {"path": "src/main/resources/application-prod.yaml"}, {"path": ".env"},
                       {"path": "../outside"}, {"path": "src/test/../../outside"},
                       {"path": "docs/README.md"}, {"path": "docs/phase-8-production-engineering.md"},
                       {"path": "src/test/.env"}, {"path": "src\\test\\java\\FixtureTest.java"},
                       {"path": "/src/test/java/FixtureTest.java"}):
            with self.subTest(change=change), self.assertRaises(gate.GateError):
                self.check(policy=self.policy | {"reviews": [self.entry | change]})

    def test_missing_approval_reason_evidence_or_timezone_rejected(self):
        for change in ({"approvedBy": ""}, {"owner": " "}, {"reason": ""}, {"evidence": ""},
                       {"evidence": "docs/missing.md#approval"}, {"evidence": "docs/review.md#missing"},
                       {"evidence": "../outside.md#approval"},
                       {"reviewedAt": "2026-10-17"}, {"expiresAt": "bad-date"}):
            with self.subTest(change=change), self.assertRaises(gate.GateError):
                self.check(policy=self.policy | {"reviews": [self.entry | change]})

    def test_future_review_exclusive_expiry_and_maximum_thirty_days(self):
        expiry = dt.datetime.fromisoformat(self.entry["expiresAt"])
        self.assertEqual(self.check(now=expiry - dt.timedelta(microseconds=1))["acceptedSecretFindings"], 1)
        for now in (expiry, expiry + dt.timedelta(days=1), self.now - dt.timedelta(days=2)):
            with self.subTest(now=now), self.assertRaises(gate.GateError):
                self.check(now=now)
        for value in (self.now + dt.timedelta(days=30), self.now - dt.timedelta(days=1)):
            with self.subTest(value=value), self.assertRaises(gate.GateError):
                self.check(policy=self.policy | {"reviews": [self.entry | {"expiresAt": value.isoformat()}]})

    def test_scanner_pin_and_configuration_changes_invalidate_review(self):
        for change in ({"gitleaksImage": "gitleaks:latest"}, {"gitleaksConfigSha256Lf": "0" * 64}):
            with self.subTest(change=change), self.assertRaises(gate.GateError):
                self.check(policy=self.policy | change)
        (self.source / "ops/ci/gitleaks.toml").write_text("different detector configuration")
        with self.assertRaises(gate.GateError):
            self.check()

    def test_unknown_fields_malformed_types_and_unbounded_approvals_rejected(self):
        for change in ({"unexpected": "field"}, {"startLine": True}, {"endLine": 2}, {"occurrences": True},
                       {"occurrences": 0}, {"sourceSha256Lf": "invalid"}):
            with self.subTest(change=change), self.assertRaises(gate.GateError):
                self.check(policy=self.policy | {"reviews": [self.entry | change]})
        for policy in ({}, self.policy | {"schemaVersion": True}, self.policy | {"reviews": [self.entry] * 8}):
            with self.subTest(policy=policy), self.assertRaises(gate.GateError):
                self.check(policy=policy)
        for change in ({"StartLine": True}, {"EndLine": None}, {"File": None}, {"RuleID": None}):
            with self.subTest(change=change), self.assertRaises(gate.GateError):
                self.check(leaks=[self.finding | change])

    def test_review_function_does_not_modify_scanner_report_or_policy(self):
        original = copy.deepcopy((self.finding, self.policy))
        self.check()
        self.assertEqual((self.finding, self.policy), original)

    def test_image_scan_has_no_source_review_acceptance(self):
        image = (Path(__file__).resolve().parents[2] / "container/image.py").read_text()
        self.assertNotIn("review_secret_findings", image)
        self.assertIn("not leaks", image)

    def test_symlink_reviewed_source_is_rejected(self):
        with patch.object(Path, "is_symlink", return_value=True), self.assertRaises(gate.GateError):
            self.check()

    def test_missing_policy_and_unreviewed_findings_are_not_silently_accepted(self):
        with self.assertRaises(gate.GateError):
            self.check(policy={})
        with self.assertRaises(gate.GateError):
            self.check(now=self.now.replace(tzinfo=None))

    def test_inline_scanner_suppression_is_not_honored_by_source_gate(self):
        source = (Path(__file__).resolve().parents[1] / "gate.py").read_text()
        self.assertIn('"--ignore-gitleaks-allow"', source)

    def test_total_approved_rows_cannot_exceed_ten(self):
        self.fixture.write_text("synthetic source line\n" * 6)
        identity = gate.source_file_sha256_lf(self.fixture)
        entries = [self.entry | {"id": f"source-fp-{index:02d}", "startLine": index,
                                 "endLine": index, "occurrences": 2, "sourceSha256Lf": identity}
                   for index in range(1, 7)]
        with self.assertRaisesRegex(gate.GateError, "approval count exceeded"):
            self.check(policy=self.policy | {"reviews": entries})


if __name__ == "__main__":
    unittest.main()
