"""Offline fault-injection ownership, invariant and privacy controls."""
import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('resilience', Path(__file__).resolve().parents[2] / 'resilience/resilience.py')
drills = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(drills)
gate = drills.gate
PROJECT = 'flowpay-stage-' + 'a' * 32


class ResilienceTest(unittest.TestCase):
    def target(self, service='app'):
        return {'Id': 'b' * 64, 'Config': {'Labels': {'com.docker.compose.project': PROJECT, 'com.docker.compose.service': service}}}

    def test_exact_owned_app_and_dependency_targets_accepted(self):
        for service in drills.ALLOWED_SERVICES:
            self.assertEqual(drills.owned_target(PROJECT, self.target(service)), 'b' * 64)

    def test_foreign_project_rejected(self):
        state = self.target()
        state['Config']['Labels']['com.docker.compose.project'] = 'development'
        with self.assertRaises(gate.GateError):
            drills.owned_target(PROJECT, state)

    def test_broad_or_non_uuid_project_rejected(self):
        for project in ('', 'flowpay', 'flowpay-stage-', '/', 'flowpay-stage-' + 'g' * 32):
            with self.subTest(project=project), self.assertRaises(gate.GateError):
                drills.owned_target(project, self.target())

    def test_missing_labels_id_or_unreviewed_service_rejected(self):
        for state in ({}, {'Id': 'b' * 64}, self.target('unrelated'), self.target('receiver')):
            with self.subTest(state=state), self.assertRaises(gate.GateError):
                drills.owned_target(PROJECT, state)
        state = self.target()
        state['Id'] = 'container-by-name'
        with self.assertRaises(gate.GateError):
            drills.owned_target(PROJECT, state)

    def test_foreign_action_never_invokes_docker(self):
        obj = drills.Drill.__new__(drills.Drill)
        obj.project = PROJECT
        state = self.target()
        state['Config']['Labels']['com.docker.compose.project'] = 'another-stage'
        with patch.object(gate, 'run') as command, self.assertRaises(gate.GateError):
            obj.action(state, 'kill')
        command.assert_not_called()

    def test_action_revalidates_identity_before_mutation(self):
        obj = drills.Drill.__new__(drills.Drill)
        obj.project = PROJECT
        obj.affected = {}
        changed = self.target()
        changed['Config']['Labels']['com.docker.compose.project'] = 'another-stage'
        with patch.object(gate, 'run', return_value=(json.dumps([changed]).encode(), 0)) as command, self.assertRaises(gate.GateError):
            obj.action(self.target(), 'stop')
        self.assertEqual(command.call_count, 1)
        self.assertEqual(command.call_args.args[0][:2], ['docker', 'inspect'])

    def test_only_fixed_actions_can_mutate_owned_container(self):
        obj = drills.Drill.__new__(drills.Drill)
        obj.project = PROJECT
        obj.affected = {}
        with patch.object(gate, 'run', return_value=(json.dumps([self.target()]).encode(), 0)) as command, self.assertRaises(gate.GateError):
            obj.action(self.target(), 'rm --force')
        self.assertEqual(command.call_count, 1)

    def test_every_case_and_two_replicas_mandatory(self):
        value = gate.document(drills.DRILLS / 'manifest.json')
        drills.validate_manifest(value)
        for mutation in ({'replicas': 1}, {'replicas': 3}, {'cases': list(drills.CASES[:-1])}, {'recoverySeconds': 3600}, {'outageSeconds': 0}):
            with self.subTest(mutation=mutation), self.assertRaises(gate.GateError):
                drills.validate_manifest({**value, **mutation})

    def test_candidate_rebinding_keeps_original_failure_scenarios_and_bounds(self):
        current = gate.document(drills.DRILLS / 'manifest.json')
        historical = gate.document(drills.DRILLS / 'history/t15/manifest.json')
        for key in ('replicas', 'host', 'readinessSeconds', 'recoverySeconds',
                    'outageSeconds', 'cases', 'policy'):
            self.assertEqual(current[key], historical[key], key)

    def test_host_envelope_records_page_variation_without_rewriting_t14(self):
        value = gate.document(drills.DRILLS / 'manifest.json')
        host = dict(value['host'], MemTotal=3920216064)
        # Keep the historical production manifest unchanged. Exercise matching
        # and changed configuration bytes in a disposable unit-test fixture.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = b'reviewed runtime fixture\n'
            (root / 'Dockerfile').write_bytes(fixture)
            gate.write_json(root / 'manifest.json', {
                'configSha256Lf': {'Dockerfile': hashlib.sha256(fixture).hexdigest()}})
            with patch.object(drills, 'ROOT', root), patch.object(drills.baseline, 'LOAD', root):
                drills.validate_host(host, value['host']['composeVersion'], value)
                drills.validate_host({**host, 'MemTotal': 3920220160}, value['host']['composeVersion'], value)
                for change in ({'MemTotal': 2 * 1024 ** 3}, {'MemTotal': 8 * 1024 ** 3}, {'NCPU': 2}, {'OSType': 'windows'}):
                    with self.subTest(change=change), self.assertRaises(gate.GateError):
                        drills.validate_host({**host, **change}, value['host']['composeVersion'], value)
                (root / 'Dockerfile').write_bytes(b'changed runtime fixture\n')
                with self.assertRaisesRegex(gate.GateError, 'runtime configuration changed'):
                    drills.validate_host(host, value['host']['composeVersion'], value)

    def test_metrics_projection_aggregates_known_numeric_counter_only(self):
        raw = b'jvm_memory_used_bytes 1\nflowpay_webhook_http_outcomes_total{outcome="HTTP_2XX"} 2.0\nflowpay_webhook_http_outcomes_total{outcome="REQUEST_TIMEOUT"} 1.0\n'
        self.assertEqual(drills.meter(raw, 'flowpay_webhook_http_outcomes_total'), 3)
        self.assertEqual(drills.meter(raw, 'flowpay_webhook_delivery_attempts_total'), 0)

    def test_missing_metrics_endpoint_is_not_valid_zero(self):
        with self.assertRaises(gate.GateError):
            drills.meter(b'gateway failed', 'flowpay_webhook_delivery_attempts_total')

    def test_nonfinite_or_negative_counter_fails(self):
        for value in (b'1e999', b'-1.0'):
            with self.subTest(value=value), self.assertRaises(gate.GateError):
                drills.meter(b'jvm_memory_used_bytes 1\nclaimed_total ' + value + b'\n', 'claimed_total')

    def test_sql_audits_are_server_side_read_only(self):
        obj = drills.Drill.__new__(drills.Drill)
        with patch.object(obj, 'compose', return_value=b't') as command:
            self.assertEqual(obj.sql('SELECT true'), b't')
        self.assertIn('PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=10000', command.call_args.args)
        audit = (drills.DRILLS / 'audit.sql').read_text()
        self.assertNotRegex(audit.upper(), r'\b(INSERT|UPDATE|DELETE|TRUNCATE)\b')
        for label in ('DELIVERY_LEASE_EXPIRED', 'lease_expires_at', 'INTERVAL \'10 seconds\'', 'idempotency_records', 'ledger_entries'):
            self.assertIn(label, audit)

    def test_receiver_safe_summary_contains_no_client_identity_key_or_digest(self):
        spec = importlib.util.spec_from_file_location('receiver', drills.DRILLS / 'receiver.py')
        receiver = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(receiver)
        receiver.STATE.update(client={'key': 'private-key-marker'}, selected='private-event-marker', keys={'/drill': 'private-secret-marker'})
        receiver.SEEN['private-event-marker'] = {'digest': b'private-payload-marker', 'attempts': 2}
        value = json.dumps(receiver.safe_stats())
        self.assertNotIn('private-', value)
        self.assertEqual(receiver.safe_stats()['duplicateRequests'], 1)

    def test_overlay_does_not_change_app_or_retry_resources(self):
        value = (drills.DRILLS / 'fixture.yaml').read_text()
        for token in ('\n  app:', 'RETRY', 'LEASE_TIMEOUT', 'ports:', 'privileged:', 'docker.sock', '.env'):
            self.assertNotIn(token, value)
        self.assertIn('secrets: !override [cert.pem, key.pem]', value)

    def test_fault_owner_selection_and_late_result_are_not_guessed_or_forced(self):
        value = (drills.DRILLS / 'resilience.py').read_text()
        self.assertIn('len(owners) == 1', value)
        self.assertIn('finished_at IS NULL', value)
        self.assertIn("value['http_status'] is None", value)
        self.assertIn('late finalizer rewrote abandoned attempt', value)
        self.assertNotIn('UPDATE outbox_events', value)
        self.assertNotIn('TRUNCATE', value)

    def test_probe_expect_does_not_export_sensitive_body(self):
        spec = importlib.util.spec_from_file_location('probe', drills.DRILLS / 'probe.py')
        probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(probe)
        with self.assertRaisesRegex(AssertionError, 'owned status expected 200, got 500') as error:
            probe.expect((500, {'secret': 'private-marker'}), 200)
        self.assertNotIn('private-marker', str(error.exception))

    def probe(self):
        spec = importlib.util.spec_from_file_location('probe', drills.DRILLS / 'probe.py')
        probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(probe)
        return probe

    def test_recovered_monitoring_requires_new_scrapes_not_old_up_cache(self):
        probe = self.probe()
        with patch.object(probe.time, 'time', return_value=1234.5), patch.object(probe, 'query', return_value=(200, {})), \
                patch.object(probe, 'prometheus', return_value=[{}, {}]) as query:
            self.assertTrue(probe.main('replicas', 'fixture')['postProbeScrapesFresh'])
        self.assertIn('timestamp(up', query.call_args_list[0].args[0])
        self.assertIn('>= 1234.5', query.call_args_list[0].args[0])

    def test_survivor_requires_valid_business_query_after_transient(self):
        probe = self.probe()
        with patch.object(probe, 'query', side_effect=[(502, None), (200, {'data': [], 'meta': {'size': 20}})]), \
                patch.object(probe.time, 'sleep'):
            self.assertTrue(probe.main('available-query', 'fixture')['survivorBusiness200'])

    def test_telemetry_outage_cannot_pass_with_reachable_listener(self):
        probe = self.probe()
        with patch.object(probe, 'http', return_value=(200, {})), self.assertRaises(AssertionError):
            probe.main('monitoring-down', 'fixture')
        with patch.object(probe, 'http', return_value=(0, None)):
            self.assertEqual(probe.main('monitoring-down', 'fixture')['stoppedTelemetryListeners'], 4)
