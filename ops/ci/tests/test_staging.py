"""Offline staging policy tests. Native TLS/DB/broker/network checks are separate."""
import datetime as dt
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import re
import subprocess

SPEC = importlib.util.spec_from_file_location('staging', Path(__file__).resolve().parents[2] / 'staging/staging.py')
staging = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(staging)
gate = staging.gate
PROJECT = 'flowpay-stage-' + 'a' * 32
IDENTITY = 'sha256:' + 'b' * 64


class StagingTest(unittest.TestCase):
    def probe(self):
        spec = importlib.util.spec_from_file_location('staging_probe', staging.STAGE / 'tests/probe.py')
        module = importlib.util.module_from_spec(spec)
        with patch('ssl.create_default_context'):
            spec.loader.exec_module(module)
        return module

    def test_probe_classifies_known_monitoring_assertions(self):
        probe = self.probe()
        for message in ('Controlled business trace not retrievable', 'Dashboard provisioning differs from versioned JSON',
                        'Sensitive fixture reached trace storage', 'Required scrape targets unavailable'):
            self.assertEqual(probe.failure_label(AssertionError(message)), 'owned monitoring: ' + message.lower())

    def test_probe_never_echoes_unknown_or_arbitrary_owned_assertions(self):
        probe = self.probe()
        for message in ('private-sensitive-value', 'owned private-sensitive-value', 'Grafana failed private-sensitive-value'):
            self.assertEqual(probe.failure_label(AssertionError(message)), 'owned staging assertion failed')

    def test_probe_dynamic_monitoring_diagnostics_drop_all_suffix_values(self):
        probe = self.probe()
        for prefix, expected in (('Expected metric not ingested: ', 'owned monitoring expected metric not ingested'),
                                 ('Dashboard query not ready: ', 'owned monitoring dashboard query failed'),
                                 ('Unexpected HTTP status on ', 'owned monitoring HTTP status mismatch')):
            self.assertEqual(probe.failure_label(AssertionError(prefix + 'private-sensitive-value')), expected)

    def test_probe_preserves_only_bounded_status_deadline_and_owned_labels(self):
        probe = self.probe()
        for message in ('owned status expected 200, got 503', 'owned deadline: readiness',
                        'owned secure cookie policy failed', 'owned duplicate changed delivery state'):
            self.assertEqual(probe.failure_label(AssertionError(message)), message)
        for message in ('owned status expected 200, got private-sensitive-value', 'owned deadline: private-sensitive-value',
                        'owned status expected 200, got 503\nprivate-sensitive-value'):
            self.assertEqual(probe.failure_label(AssertionError(message)), 'owned staging assertion failed')

    def config(self):
        memberships = {'app': ['ingress', 'data', 'observe', 'webhook'], 'edge': ['front', 'ingress'],
                       'postgres': ['data'], 'rabbitmq': ['data', 'observe'], 'topology': ['data'],
                       'egress': ['webhook', 'outbound'], 'prometheus': ['observe'], 'grafana': ['observe'],
                       'collector': ['observe'], 'tempo': ['observe']}
        services = {name: {'image': IDENTITY, 'networks': {network: {} for network in networks}, 'mem_limit': 100,
                           'cpus': 1, 'volumes': []} for name, networks in memberships.items()}
        services['edge']['ports'] = [{'host_ip': '127.0.0.1', 'target': 8443, 'published': '18443'}]
        services['app'].update(read_only=True, cap_drop=['ALL'], pids_limit=256, secrets=[{'source': 'DB_PASSWORD'}],
                               environment={'SPRING_PROFILES_ACTIVE': 'prod', 'WEBHOOK_EGRESS_PROXY_HOST': 'egress',
                                            'RABBITMQ_USERNAME': 'flowpay_stage'},
                               depends_on={'topology': {'condition': 'service_completed_successfully'}})
        return {'services': services, 'networks': {name: {'name': PROJECT + '_' + name, 'internal': name not in ('front', 'outbound'),
                                                       'enable_ipv6': True} for name in ('front', 'ingress', 'data', 'observe', 'webhook', 'outbound')},
                'volumes': {'postgres-data': {'name': PROJECT + '_postgres-data'}}}

    def rejected(self, change):
        config = self.config()
        change(config)
        with self.assertRaises(gate.GateError):
            staging.validate_config(config, PROJECT)

    def test_reviewed_private_topology_accepted(self):
        staging.validate_config(self.config(), PROJECT)

    def test_unknown_services_rejected(self):
        self.rejected(lambda config: config['services'].update(unreviewed={'image': IDENTITY}))

    def test_database_broker_management_and_monitoring_ports_cannot_publish(self):
        for name in ('app', 'postgres', 'rabbitmq', 'prometheus', 'grafana', 'collector', 'tempo', 'egress'):
            with self.subTest(name=name):
                self.rejected(lambda config: config['services'][name].update(ports=[{'target': 1234}]))

    def test_edge_cannot_bind_public_interface_or_plaintext(self):
        for value in ({'host_ip': '0.0.0.0', 'target': 8443}, {'host_ip': '127.0.0.1', 'target': 8080}):
            self.rejected(lambda config: config['services']['edge'].update(ports=[value]))

    def test_private_network_cannot_become_outbound(self):
        self.rejected(lambda config: config['networks']['data'].update(internal=False))

    def test_application_cannot_join_public_or_outbound_network(self):
        for name in ('front', 'outbound'):
            self.rejected(lambda config: config['services']['app']['networks'].update({name: {}}))

    def test_shared_development_volume_or_network_rejected(self):
        self.rejected(lambda config: config['volumes']['postgres-data'].update(name='development-data'))
        self.rejected(lambda config: config['networks']['data'].update(external=True))

    def test_fixed_names_host_network_and_privileged_rejected(self):
        for value in ({'container_name': 'flowpay-app'}, {'network_mode': 'host'}, {'privileged': True}):
            self.rejected(lambda config: config['services']['app'].update(value))

    def test_floating_images_rejected(self):
        self.rejected(lambda config: config['services']['app'].update(image='flowpay:latest'))

    def test_unbounded_resources_rejected(self):
        for key in ('mem_limit', 'cpus'):
            self.rejected(lambda config: config['services']['app'].update({key: 0}))

    def test_host_socket_env_and_writable_mounts_rejected(self):
        for value in ({'source': '/var/run/docker.sock', 'type': 'bind', 'read_only': True},
                      {'source': '/secrets/.env', 'type': 'bind', 'read_only': True},
                      {'source': '/source', 'type': 'bind', 'read_only': False}):
            self.rejected(lambda config: config['services']['app'].update(volumes=[value]))

    def test_admin_broker_credential_not_available_to_app(self):
        self.rejected(lambda config: config['services']['app']['secrets'].append({'source': 'RABBITMQ_ADMIN_PASSWORD'}))

    def test_topology_must_complete_before_producers(self):
        self.rejected(lambda config: config['services']['app']['depends_on']['topology'].update(condition='service_started'))

    def test_nonprod_or_missing_egress_policy_rejected(self):
        self.rejected(lambda config: config['services']['app']['environment'].update(SPRING_PROFILES_ACTIVE='dev'))
        self.rejected(lambda config: config['services']['app']['environment'].update(WEBHOOK_EGRESS_PROXY_HOST='localhost'))

    def test_sandbox_cannot_weaken(self):
        for value in ({'read_only': False}, {'cap_drop': []}, {'pids_limit': 0}):
            self.rejected(lambda config: config['services']['app'].update(value))

    def test_foreign_cleanup_target_rejected_before_docker_invocation(self):
        with patch.object(gate, 'run') as invoked, self.assertRaises(gate.GateError):
            staging.assert_empty_project('flowpay-dev')
        invoked.assert_not_called()

    def test_existing_project_is_never_adopted_or_deleted(self):
        with patch.object(gate, 'run', return_value=(b'preexisting', 0)) as invoked, self.assertRaises(gate.GateError):
            staging.assert_empty_project(PROJECT)
        self.assertTrue(all(call.args[0][2] == 'ls' for call in invoked.call_args_list))

    def test_empty_uuid_project_inventory_checked(self):
        with patch.object(gate, 'run', return_value=(b'', 0)) as invoked:
            staging.assert_empty_project(PROJECT)
        self.assertEqual(invoked.call_count, 3)
        self.assertIn('--all', invoked.call_args_list[0].args[0])

    def reports(self):
        return {name: {'status': 'passed', 'imageId': IDENTITY, 'timestamp': dt.datetime.now(dt.timezone.utc).isoformat(),
                       'sourceCommit': 'abc123', 'trackedWorktreeDirty': False,
                       'secretFindings': 0, 'acceptedSecretFindings': 0, 'unreviewedSecretFindings': 0,
                       'cases': {'fresh': {'status': 'passed'}, 'phase7': {'status': 'passed'}}}
                for name in ('security', 'image-scan', 'image-runtime', 'staging')}

    def release(self, reports, dirty=b''):
        with patch.object(staging.image, 'image_identity', return_value=IDENTITY), \
                patch.object(gate, 'document', side_effect=lambda path: reports[path.stem]), \
                patch.object(gate, 'run', side_effect=[(b'abc123', 0), (dirty, 0)]):
            staging.release_check({})

    def test_only_matching_fresh_clean_release_gates_accepted(self):
        self.release(self.reports())

    def test_fresh_security_report_cannot_release_expired_or_missing_source_review_deadline(self):
        reports = self.reports()
        source = reports['security']
        source.update(secretFindings=1, acceptedSecretFindings=1, unreviewedSecretFindings=0)
        for expiry in (None, (dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=1)).isoformat()):
            source['acceptedSecretReviewExpiresAt'] = expiry
            with self.subTest(expiry=expiry), self.assertRaises(gate.GateError):
                self.release(reports)
        source['acceptedSecretReviewExpiresAt'] = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=1)).isoformat()
        self.release(reports)

    def test_blocked_source_or_image_cannot_be_overridden(self):
        for name in ('security', 'image-scan', 'image-runtime', 'staging'):
            reports = self.reports()
            reports[name]['status'] = 'failed'
            with self.assertRaises(gate.GateError):
                self.release(reports)

    def test_stale_different_image_or_dirty_source_rejected(self):
        reports = self.reports()
        reports['image-scan']['imageId'] = 'sha256:' + 'c' * 64
        with self.assertRaises(gate.GateError):
            self.release(reports)
        reports = self.reports()
        reports['security']['timestamp'] = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=4)).isoformat()
        with self.assertRaises(gate.GateError):
            self.release(reports)
        with self.assertRaises(gate.GateError):
            self.release(self.reports(), dirty=b'changed')

    def test_partial_staging_acceptance_cannot_release(self):
        reports = self.reports()
        reports['staging']['cases'].pop('phase7')
        with self.assertRaises(gate.GateError):
            self.release(reports)

    def test_topology_has_six_source_keys_two_success_bindings_and_separate_dlqs(self):
        definitions = gate.document(staging.STAGE / 'rabbit-definitions.json')
        webhook = [item for item in definitions['bindings'] if item['destination'] == 'flowpay.webhook.events']
        self.assertEqual(len(webhook), 6)
        self.assertEqual({item['routing_key'] for item in webhook}, {kind + '.' + state + '.v1' for kind in ('payment', 'refund') for state in ('processing', 'succeeded', 'failed')})
        self.assertEqual(len(definitions['bindings']), 10)
        queues = {item['name']: item for item in definitions['queues']}
        self.assertNotEqual(queues['flowpay.ledger.events']['arguments']['x-dead-letter-routing-key'], queues['flowpay.webhook.events']['arguments']['x-dead-letter-routing-key'])

    def test_edge_preserves_approved_problem_contract_and_proxy_policy(self):
        old = (staging.ROOT / 'ops/ingress/nginx.conf').read_text()
        new = (staging.STAGE / 'nginx.conf').read_text()
        pattern = r"return 429 '([^']*)';"
        self.assertEqual(re.findall(pattern, old), re.findall(pattern, new))
        for fragment in ('rate=10r/m', 'rate=10r/s', 'burst=5 nodelay', 'burst=20 nodelay', 'ssl_protocols TLSv1.2 TLSv1.3', 'server app:8080 resolve'):
            self.assertIn(fragment, new)
        self.assertNotIn('real_ip_header', new)

    def test_smoke_has_no_security_skip_or_cloud_publish_flags(self):
        source = (staging.STAGE / 'staging.py').read_text()
        self.assertNotIn('--skip-security', source)
        self.assertNotIn("'push'", source)

    def test_compose_failure_never_echoes_third_party_secret_diagnostics(self):
        runner = staging.Smoke.__new__(staging.Smoke)
        runner.command = ['docker', 'compose']
        result = subprocess.CompletedProcess(runner.command, 1, b'', b'permission denied: private-sensitive-value')
        with patch.object(staging.subprocess, 'run', return_value=result), self.assertRaises(gate.GateError) as error:
            runner.compose('up')
        self.assertEqual(str(error.exception), 'owned staging command failed: permission denied')

    def test_compose_timeout_has_only_fixed_diagnostic(self):
        runner = staging.Smoke.__new__(staging.Smoke)
        runner.command = ['docker', 'compose']
        failure = subprocess.TimeoutExpired(runner.command, 1, stderr=b'private-sensitive-value')
        with patch.object(staging.subprocess, 'run', side_effect=failure), self.assertRaises(gate.GateError) as error:
            runner.compose('up')
        self.assertEqual(str(error.exception), 'owned staging command unavailable or timed out')

    def test_ci_verification_checks_out_legacy_migration_history(self):
        workflow = (staging.ROOT / '.github/workflows/ci.yml').read_text()
        verification = workflow.split('  verify:', 1)[1].split('  supply-chain:', 1)[0]
        self.assertIn('fetch-depth: 0', verification)
        self.assertIn('persist-credentials: false', verification)
