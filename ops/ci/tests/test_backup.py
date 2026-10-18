"""Offline DR target/path/archive/authentication/reconciliation/privacy controls."""
import importlib.util
import io
import copy
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('backup', Path(__file__).resolve().parents[2] / 'backup/backup.py')
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)
gate, archive = backup.gate, backup.archives


class BackupTest(unittest.TestCase):
    def probe(self):
        spec = importlib.util.spec_from_file_location('backup_probe', backup.HERE / 'probe.py')
        result = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(result)
        return result

    def record(self, parent=None):
        return {'traceparent': parent, 'envelope': {'eventId': 'owned', 'eventType': 'payment.succeeded.v1',
                'aggregateType': 'PAYMENT', 'aggregateId': 'owned-payment', 'occurredAt': '2026-10-17T00:00:00Z', 'payload': {}}}

    def test_original_publication_retains_identity_headers_and_no_baggage(self):
        parent = '00-' + 'a' * 32 + '-' + 'b' * 16 + '-01'
        value = self.probe().publication(self.record(parent))
        self.assertEqual(value['properties']['message_id'], 'owned')
        self.assertEqual(value['properties']['headers']['traceparent'], parent)
        self.assertEqual(value['properties']['delivery_mode'], 2)
        self.assertEqual(set(value['properties']['headers']), {'traceparent', 'x-flowpay-event-type', 'x-flowpay-aggregate-type', 'x-flowpay-aggregate-id'})
        self.assertNotIn('traceparent', json.loads(value['payload']))

    def test_original_publication_optional_context_does_not_invent_a_parent(self):
        self.assertNotIn('traceparent', self.probe().publication(self.record())['properties']['headers'])

    def test_original_publication_rejects_invalid_zero_or_nonstring_context(self):
        for parent in ('invalid', 42, '00-' + '0' * 32 + '-' + 'b' * 16 + '-01', '00-' + 'a' * 32 + '-' + '0' * 16 + '-01'):
            with self.subTest(parent=parent), self.assertRaises(AssertionError):
                self.probe().publication(self.record(parent))

    def test_synthetic_key_copy_remains_readable_to_nonroot_app_on_linux(self):
        with tempfile.TemporaryDirectory() as directory:
            obj = backup.Recovery.__new__(backup.Recovery)
            obj.secrets = Path(directory)
            path = obj.secrets / 'WEBHOOK_SECRET_ENCRYPTION_KEY'
            path.write_bytes(b'initial-synthetic')
            try:
                obj.write_synthetic_key(b'changed-synthetic')
                self.assertEqual(path.read_bytes(), b'changed-synthetic')
                self.assertEqual(path.stat().st_mode & 0o444, 0o444)
            finally:
                path.chmod(0o600)

    def test_policy_is_recorded_before_acceptance(self):
        policy = gate.document(backup.HERE / 'policy.json')
        backup.validate_policy(policy)
        for change in ({'rtoSeconds': 1800}, {'rpoSeconds': 0}, {'cadenceHours': 48}, {'retention': {}},
                       {'replay': 'automatic'}, {'rabbitNode': 'renamed'}, {'version': 'new-unapproved'}):
            with self.subTest(change=change), self.assertRaises(gate.GateError):
                backup.validate_policy({**policy, **change})

    def test_candidate_rebinding_preserves_delegated_recovery_and_replay_scope(self):
        current = gate.document(backup.HERE / 'policy.json')
        historical = gate.document(backup.HERE / 'history/t16/policy.json')
        for key in ('version', 'approvedOn', 'approval', 'rpoSeconds', 'rtoSeconds',
                    'cadenceHours', 'retention', 'storage', 'replay', 'rabbitNode',
                    'rtoClock', 'rpoClock'):
            self.assertEqual(current[key], historical[key], key)

    def test_repo_and_relative_plaintext_paths_rejected(self):
        for path in (backup.ROOT, backup.ROOT / 'target', Path('relative')):
            with self.subTest(path=path), self.assertRaises(gate.GateError):
                backup.external(path)

    def test_external_temp_path_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(backup.external(directory), Path(directory))

    def make_tar(self, directory, names):
        path = Path(directory) / 'test.tar'
        with tarfile.open(path, 'w') as output:
            for value in names:
                if isinstance(value, str):
                    item = tarfile.TarInfo(value)
                    item.size = 1
                    output.addfile(item, io.BytesIO(b'x'))
                else:
                    output.addfile(value)
        return path

    def rejected(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            path = self.make_tar(directory, entries)
            dest = Path(directory) / 'empty'
            dest.mkdir()
            with self.assertRaises(ValueError):
                archive.unpack(path, dest)
            self.assertEqual(list(dest.iterdir()), [])

    def test_archive_traversal_absolute_windows_and_ambiguous_paths_rejected_before_writes(self):
        for name in ('../evil', '/absolute', 'C:/evil', 'a\\b', './a', 'a/../b', 'a//b', ''):
            with self.subTest(name=name):
                self.rejected(['safe', name])

    def test_archive_duplicate_and_file_ancestor_rejected_before_writes(self):
        for entries in (['a', 'a'], ['a', 'a/b']):
            self.rejected(entries)

    def test_links_devices_and_fifo_rejected_before_writes(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.BLKTYPE, tarfile.FIFOTYPE):
            with self.subTest(kind=kind):
                item = tarfile.TarInfo('evil')
                item.type = kind
                item.linkname = '../outside'
                self.rejected(['safe', item])

    def test_archive_count_size_and_owner_bounds(self):
        with patch.object(archive, 'MAX_FILES', 1):
            self.rejected(['a', 'b'])
        with patch.object(archive, 'MAX_BYTES', 1):
            self.rejected(['a', 'b'])
        for field in ('uid', 'gid'):
            item = tarfile.TarInfo('bad-owner')
            setattr(item, field, 70000)
            self.rejected([item])

    def test_pack_unpack_roundtrip_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            src, dst = root / 'source', root / 'destination'
            src.mkdir()
            dst.mkdir()
            (src / 'nested').mkdir()
            (src / 'nested/data').write_bytes(b'private-fixture')
            path = root / 'data.tar'
            archive.pack(src, path)
            archive.unpack(path, dst)
            self.assertEqual((dst / 'nested/data').read_bytes(), b'private-fixture')
            with self.assertRaises(ValueError):
                archive.pack(src, path)
            with self.assertRaises(ValueError):
                archive.unpack(path, dst)

    def test_failed_aead_destroys_partial_plaintext_never_promotes_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            crypto = backup.Crypto(root)
            (root / 'data').mkdir()
            (root / 'data/private.pem').write_bytes(b'fixture-private-key')
            source, destination = root / 'bundle.cms', root / 'restored.tar'
            source.write_bytes(b'fixture-encrypted')
            def fail(*args, **kwargs):
                destination.with_suffix('.quarantine').write_bytes(b'unauthenticated-plaintext')
                raise gate.GateError('command failed; raw output withheld')
            with patch.object(crypto, 'openssl', side_effect=fail), self.assertRaises(gate.GateError):
                crypto.decrypt(source, destination, 'data', gate.sha256(source))
            self.assertFalse(destination.exists())
            self.assertFalse(destination.with_suffix('.quarantine').exists())

    def test_bad_catalog_missing_key_and_existing_destination_never_start_openssl(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            crypto = backup.Crypto(root)
            source, destination = root / 'bundle.cms', root / 'restore.tar'
            source.write_bytes(b'fixture')
            with patch.object(crypto, 'openssl') as command:
                for digest in ('bad-digest', gate.sha256(source)):
                    with self.assertRaises(gate.GateError):
                        crypto.decrypt(source, destination, 'data', digest)
                (root / 'data').mkdir()
                (root / 'data/private.pem').write_bytes(b'fixture')
                destination.write_bytes(b'existing')
                with self.assertRaises(gate.GateError):
                    crypto.decrypt(source, destination, 'data', gate.sha256(source))
                command.assert_not_called()

    def test_cross_directory_or_unknown_crypto_role_never_invokes_openssl(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'sub').mkdir()
            crypto = backup.Crypto(root)
            with patch.object(crypto, 'openssl') as command:
                for source, destination, role in ((root / 'sub/data', root / 'plain', 'data'),
                                                  (root / 'data', root / 'sub/plain', 'data'),
                                                  (root / 'data', root / 'plain', '../outside')):
                    with self.assertRaises(gate.GateError):
                        crypto.decrypt(source, destination, role, 'untrusted')
                command.assert_not_called()

    def test_no_plaintext_survives_second_bundle_key_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            crypto = backup.Crypto(root)
            def decrypt(source, destination, role, expected):
                if role == 'keys':
                    raise gate.GateError('missing key')
                destination.write_bytes(b'private-data')
            with patch.object(crypto, 'decrypt', side_effect=decrypt), self.assertRaises(gate.GateError):
                backup.authenticate(crypto, {'data': 'd', 'keys': 'k'}, root)
            self.assertFalse((root / 'restored-data.tar').exists())
            self.assertFalse((root / 'restored-keys.tar').exists())

    def test_matching_checkpoint_requires_no_replay(self):
        queues = {'ledger': {'ready': 2, 'unacked': 0}}
        self.assertFalse(backup.reconcile(queues, queues, gate.document(backup.HERE / 'policy.json')))

    def audit_fixture(self):
        return {'tables': {'merchant_api_keys': [{'id': 1, 'key_hash': 'private-fixture', 'last_used_at': '2026-10-17T00:00:00+00:00'}],
                           'outbox_events': [{'status': 'PUBLISHED'}]},
                'schema': {'constraints': ['positive-amount'], 'indexes': ['unique-event']},
                'sequences': [{'sequencename': 'owned', 'last_value': 5}]}

    def test_real_auth_usage_and_forward_sequences_allowed_only_after_exact_restore(self):
        before = self.audit_fixture()
        after = copy.deepcopy(before)
        after['tables']['merchant_api_keys'][0].update(last_used_at='2026-10-17T00:01:00+00:00')
        after['sequences'][0]['last_value'] = 6
        self.assertTrue(backup.unchanged_after_api_replay(before, after))

    def test_replay_audit_rejects_credentials_financial_history_and_backwards_counters(self):
        before = self.audit_fixture()
        mutations = (lambda x: x['tables']['merchant_api_keys'][0].update(key_hash='changed'),
                     lambda x: x['tables']['merchant_api_keys'][0].update(version=0),
                     lambda x: x['tables']['merchant_api_keys'][0].update(last_used_at=None),
                     lambda x: x['tables']['outbox_events'][0].update(status='PENDING'),
                     lambda x: x['sequences'][0].update(last_value=4),
                     lambda x: x['schema'].update(constraints=[]),
                     lambda x: x['sequences'][0].update(sequencename='foreign'))
        for mutate in mutations:
            after = copy.deepcopy(before)
            mutate(after)
            self.assertFalse(backup.unchanged_after_api_replay(before, after))

    def test_mismatch_stays_blocked_without_explicit_operator_approval(self):
        expected, actual = {'ledger': {'ready': 2, 'unacked': 0}}, {'ledger': {'ready': 0, 'unacked': 0}}
        with self.assertRaisesRegex(gate.GateError, 'consumers remain disabled'):
            backup.reconcile(expected, actual, gate.document(backup.HERE / 'policy.json'))
        self.assertTrue(backup.reconcile(expected, actual, gate.document(backup.HERE / 'policy.json'), approve=True))
        with self.assertRaises(gate.GateError):
            backup.reconcile(expected, actual, {'replay': 'automatic'}, approve=True)

    def test_foreign_queue_inventory_always_rejected(self):
        with self.assertRaises(gate.GateError):
            backup.reconcile({'owned': {'ready': 0}}, {}, {'replay': 'original-event-facts-isolated-lab-only'}, approve=True)

    def test_definition_order_is_not_a_fact_but_credentials_and_queue_arguments_are(self):
        before = {'queues': [{'name': 'a'}, {'name': 'b'}], 'users': [{'password_hash': 'private-fixture'}]}
        after = copy.deepcopy(before)
        after['queues'].reverse()
        self.assertEqual(backup.canonical_definitions(before), backup.canonical_definitions(after))
        after['users'][0]['password_hash'] = 'changed'
        self.assertNotEqual(backup.canonical_definitions(before), backup.canonical_definitions(after))

    def test_reference_schema_preserves_inventory_enforcement_and_noncheck_definitions(self):
        original = {'columns': ['minor-bigint-not-null'], 'indexes': ['unique-reference'],
                    'constraints': [{'name': 'owned', 'type': 'c', 'validated': True, 'definition': 'CHECK cast(array)'}]}
        reference = copy.deepcopy(original)
        reference['constraints'][0]['definition'] = 'CHECK array(cast(elements))'
        backup.validate_reference_schema(original, reference)
        for mutate in (lambda x: x.update(columns=[]), lambda x: x.update(indexes=[]),
                       lambda x: x['constraints'][0].update(validated=False),
                       lambda x: x['constraints'][0].update(name='foreign')):
            bad = copy.deepcopy(reference)
            mutate(bad)
            with self.assertRaises(gate.GateError):
                backup.validate_reference_schema(original, bad)
        original['constraints'][0]['type'] = reference['constraints'][0]['type'] = 'u'
        with self.assertRaises(gate.GateError):
            backup.validate_reference_schema(original, reference)

    def test_backup_inventory_covers_key_domain_history_and_flyway(self):
        self.assertEqual(len(backup.TABLES), 19)
        self.assertEqual(len(set(backup.TABLES)), 19)
        for name in ('webhook_delivery_attempts', 'webhook_endpoints', 'refresh_tokens', 'ledger_entries', 'outbox_events', 'flyway_schema_history'):
            self.assertIn(name, backup.TABLES)

    def test_composite_keys_do_not_assume_id_columns(self):
        self.assertEqual(backup.ORDER_BY['merchant_members'], 'merchant_id,user_id')
        self.assertEqual(backup.ORDER_BY['webhook_endpoint_events'], 'endpoint_id,event_type')
        self.assertEqual(backup.ORDER_BY['flyway_schema_history'], 'installed_rank')

    def test_code_has_no_financial_state_correction_or_destructive_restore_flags(self):
        code = (backup.HERE / 'backup.py').read_text()
        for token in ('UPDATE outbox_events', 'TRUNCATE', '--clean', '--create', 'docker.sock', 'git add', 'print(raw', 'print(key'):
            self.assertNotIn(token, code)
        self.assertIn("'--no-owner', '--no-acl', '--exit-on-error'", code)
        self.assertIn('restore refuses nonempty target database', code)
        self.assertIn('gate.require(fingerprint() == digest', code)

    def test_broker_restore_revalidates_ownership_and_stopped_volume(self):
        obj = backup.Recovery.__new__(backup.Recovery)
        obj.project = 'flowpay-stage-' + 'a' * 32
        obj.rabbit_image = 'pinned-fixture'
        state = {'State': {'Running': True, 'OOMKilled': False, 'ExitCode': 0}, 'Config': {'Image': obj.rabbit_image}}
        with patch.object(obj, 'states', return_value=[state]), patch.object(backup, 'helper') as command, self.assertRaises(gate.GateError):
            obj.broker_archive(Path(tempfile.gettempdir()), 'unpack')
        command.assert_not_called()

    def test_foreign_volume_name_never_inspected_or_mutated(self):
        obj = backup.Recovery.__new__(backup.Recovery)
        obj.project = 'flowpay-stage-' + 'a' * 32
        obj.rabbit_image = 'pinned-fixture'
        state = {'State': {'Running': False, 'OOMKilled': False, 'ExitCode': 0}, 'Config': {'Image': obj.rabbit_image},
                 'Mounts': [{'Destination': '/var/lib/rabbitmq', 'Type': 'volume', 'Name': 'development_rabbit-data'}]}
        with patch.object(obj, 'states', return_value=[state]), patch.object(gate, 'run') as command, self.assertRaises(gate.GateError):
            obj.volume()
        command.assert_not_called()
