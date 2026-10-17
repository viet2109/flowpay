"""Runbook source/link contracts, read-only commands and privacy negatives."""
import importlib.util
import json
from pathlib import Path
import re
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('runbooks', Path(__file__).resolve().parents[2] / 'runbooks/runbooks.py')
ops = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ops)
PROJECT = 'flowpay-stage-' + 'a' * 32


class RunbooksTest(unittest.TestCase):
    def test_rehearsal_keeps_inherited_queue_probe_contract(self):
        spec = importlib.util.spec_from_file_location('operator_probe', ops.HERE / 'probe.py')
        probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(probe)
        with patch.object(probe.backup, 'main', return_value={'owned-queue': {'ready': 0}}) as delegate:
            self.assertEqual(probe.main('queues', 'fixture'), {'owned-queue': {'ready': 0}})
        delegate.assert_called_once_with('queues', 'fixture')

    def target(self, service='app'):
        return {'Id': 'b' * 64, 'Config': {'Labels': {'com.docker.compose.project': PROJECT, 'com.docker.compose.service': service}},
                'State': {'Running': True, 'Paused': False, 'OOMKilled': False}}

    def test_actual_links_anchors_source_and_policy_contracts(self):
        result = ops.check()
        self.assertEqual(result['documents'], 2)
        self.assertGreater(result['localLinks'], 10)

    def test_invalid_project_rejected_before_any_docker_call(self):
        for project in (None, '', '/', 'flowpay', 'development', 'flowpay-stage-' + 'g' * 32):
            with self.subTest(project=project), patch.object(ops.gate, 'run') as command, self.assertRaises(ops.gate.GateError):
                ops.containers(project)
            command.assert_not_called()

    def test_foreign_project_service_and_id_rejected(self):
        for field, value in (('com.docker.compose.project', 'development'), ('com.docker.compose.service', 'unreviewed')):
            target = self.target()
            target['Config']['Labels'][field] = value
            with self.assertRaises(ops.gate.GateError):
                ops.owned(PROJECT, target, 'app')
        target = self.target()
        target['Id'] = 'mutable-name'
        with self.assertRaises(ops.gate.GateError):
            ops.owned(PROJECT, target, 'app')

    def test_exec_rechecks_labels_before_command(self):
        target = self.target()
        changed = self.target()
        changed['Config']['Labels']['com.docker.compose.project'] = 'foreign'
        with patch.object(ops.gate, 'run', return_value=(json.dumps([changed]).encode(), 0)) as command, self.assertRaises(ops.gate.GateError):
            ops.execute(PROJECT, target, 'app', ['curl'])
        self.assertEqual(command.call_count, 1)

    def test_paused_or_stopped_target_never_receives_exec(self):
        for changes in ({'Running': False}, {'Paused': True}):
            target = self.target()
            target['State'].update(changes)
            with patch.object(ops.gate, 'run', return_value=(json.dumps([target]).encode(), 0)) as command, self.assertRaises(ops.gate.GateError):
                ops.execute(PROJECT, target, 'app', ['curl'])
            self.assertEqual(command.call_count, 1)

    def test_count_projection_accepts_only_exact_nonnegative_integer_fields(self):
        value = dict.fromkeys(ops.COUNTS, 0)
        self.assertEqual(ops.durable_projection(value), value)
        for change in ({'payments': -1}, {'payments': True}, {'payments': 'private-marker'}, {'payload': 'private-marker'}):
            with self.subTest(change=change), self.assertRaises(ops.gate.GateError) as error:
                ops.durable_projection({**value, **change})
            self.assertNotIn('private-marker', str(error.exception))
        value.pop('payments')
        with self.assertRaises(ops.gate.GateError):
            ops.durable_projection(value)

    def test_queue_projection_never_exports_extra_raw_metadata(self):
        value = [{'name': name, 'messages_ready': 0, 'messages_unacknowledged': 0, 'consumers': 1, 'private': 'private-marker'} for name in ops.QUEUES]
        self.assertNotIn('private-marker', json.dumps(ops.queue_projection(value)))
        for bad in ([], value[:3], value + value[:1]):
            with self.assertRaises(ops.gate.GateError):
                ops.queue_projection(bad)
        value[0]['messages_ready'] = -1
        with self.assertRaises(ops.gate.GateError):
            ops.queue_projection(value)

    def test_metrics_drop_arbitrary_labels_nonheap_and_secrets(self):
        raw = b'process_cpu_usage{private="private-marker"} 0.1\njvm_memory_used_bytes{area="heap",id="private-marker"} 100\njvm_memory_used_bytes{area="nonheap"} 500\nsecret_marker{key="private-marker"} 42\n'
        value = ops.metric_projection(raw)
        self.assertEqual(value, {'process_cpu_usage': [0.1], 'jvm_memory_used_bytes': [100.0]})
        self.assertNotIn('private-marker', json.dumps(value))

    def test_nonfinite_and_negative_samples_are_unknown_not_zero(self):
        value = ops.metric_projection(b'flowpay_metrics_sample_age_seconds NaN\nhikaricp_connections_pending +Inf\njvm_memory_max_bytes{area="heap"} -1\n')
        self.assertTrue(all(values == [None] for values in value.values()))

    def test_empty_metrics_are_not_healthy(self):
        with self.assertRaises(ops.gate.GateError):
            ops.metric_projection(b'# no samples\n')

    def test_fixed_sql_is_one_select_and_omits_sensitive_columns(self):
        text = re.sub(r'--[^\n]*', '', ops.sql_text())
        for token in ('secret_ciphertext', 'key_hash', 'request_hash', 'payload', 'password', 'public_id'):
            self.assertNotIn(token, text)
        self.assertIn('unknownTransactions', text)
        self.assertIn('unbalancedPostings', text)

    def test_unavailable_components_do_not_become_zero_counts(self):
        states = {'postgres': [self.target('postgres')], 'rabbitmq': [self.target('rabbitmq')], 'app': [self.target()]}
        with patch.object(ops, 'containers', return_value=states), patch.object(ops, 'execute', side_effect=ops.gate.GateError('unavailable')):
            value = ops.collect(PROJECT)
        self.assertEqual(value['durable'], {'status': 'unavailable'})
        self.assertEqual(value['broker'], {'status': 'unavailable'})
        self.assertEqual(value['replicas'][0]['metrics'], {'status': 'unavailable'})

    def test_documentation_preserves_financial_and_approval_boundaries(self):
        text = (ops.ROOT / 'docs/operations-runbook.md').read_text(encoding='utf-8')
        for phrase in ('UNKNOWN financial outcome', 'no implemented', 'No generic admin API', 'PUBLISHED',
                       'not standing authority', 'no automatic re-encryption', 'financial outcomes are correct', 'No shortened retry fixture'):
            self.assertTrue(phrase.lower() in text.lower(), 'required operational boundary missing')

    def test_rehearsal_has_no_ownership_adoption_or_repair(self):
        text = (ops.HERE / 'rehearse.py').read_text(encoding='utf-8')
        self.assertIn('gate.require(before == after', text)
        self.assertIn('assert_empty_project', text)
        self.assertIn('context.cleanup()', text)
        for token in ('UPDATE ', 'TRUNCATE', 'PUBLISHED\' TO', 'duplicate_replay', 'releasePreflightPassed'):
            self.assertNotIn(token, text)

    def test_release_checklist_never_claims_release_permission(self):
        text = (ops.ROOT / 'docs/release-checklist.md').read_text(encoding='utf-8')
        self.assertIn('NO-GO', text)
        self.assertIn('T18', text)
        self.assertIn('age<=72h', text)
        self.assertIn('off-host', text)
