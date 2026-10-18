"""P8-T14 offline controls; real k6/TLS/PG/broker runs are separate evidence."""
import copy
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('benchmark', Path(__file__).resolve().parents[2] / 'load/benchmark.py')
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)
gate = benchmark.gate


class LoadTest(unittest.TestCase):
    def setUp(self):
        self.manifest = gate.document(benchmark.LOAD / 'manifest.json')
        # Unit controls validate a synthetic approved configuration, not whether
        # today's checkout is still the historical T13 performance artifact.
        # The real CLI retains that exact-artifact/configuration rejection.
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.root = Path(work.name)
        fixture = b'reviewed runtime fixture\n'
        (self.root / 'Dockerfile').write_bytes(fixture)
        self.manifest['configSha256Lf'] = {'Dockerfile': hashlib.sha256(fixture).hexdigest()}
        context = patch.object(benchmark, 'ROOT', self.root)
        context.start()
        self.addCleanup(context.stop)

    def valid_manifest(self, manifest=None):
        manifest = manifest or self.manifest
        benchmark.validate_manifest(manifest, self.manifest['target'], self.manifest['target']['composeVersion'])

    def test_matching_approved_configuration_valid(self):
        self.valid_manifest()

    def test_reacceptance_preserves_historical_targets_and_workload(self):
        historical = gate.document(benchmark.LOAD / 'history/t14/manifest.json')
        current = gate.document(benchmark.LOAD / 'manifest.json')
        for key in ('workflow', 'profiles', 'retryPolicy', 'calibrationProfiles',
                    'acceptanceProfiles', 'hardDrainSeconds', 'requestTimeoutSeconds',
                    'preAllocatedVUs', 'workflowMaxVUs', 'queryMaxVUs',
                    'gracefulStopSeconds', 'generatorImage', 'generatorMemoryMiB', 'generatorCpu'):
            self.assertEqual(current[key], historical[key], key)
        self.assertNotEqual(current['applicationImage'], historical['applicationImage'])
        self.assertNotEqual(current['jarSha256'], historical['jarSha256'])
        original = gate.document(benchmark.LOAD / 'history/t14/acceptance.json')
        active = gate.document(benchmark.LOAD / 'acceptance.json')
        proposal = gate.document(benchmark.LOAD / 'proposal.json')
        self.assertEqual(original['status'], 'APPROVED')
        self.assertEqual(active['thresholds'], original['thresholds'])
        self.assertEqual(proposal['thresholds'], original['thresholds'])
        self.assertNotEqual(benchmark.workload_digest(), original['workloadSha256'])

    def test_changed_configuration_bytes_require_new_review(self):
        (self.root / 'Dockerfile').write_bytes(b'changed runtime fixture\n')
        with self.assertRaisesRegex(gate.GateError, 'configuration changed'):
            self.valid_manifest()

    def test_host_cpu_memory_kernel_platform_or_version_mismatch_rejected(self):
        for key in ('NCPU', 'MemTotal', 'KernelVersion', 'Architecture', 'ServerVersion', 'OSType'):
            host = dict(self.manifest['target'])
            host[key] = 'different'
            with self.subTest(key=key), self.assertRaises(gate.GateError):
                benchmark.validate_manifest(self.manifest, host, self.manifest['target']['composeVersion'])

    def test_floating_generator_rejected(self):
        self.manifest['generatorImage'] = 'grafana/k6:latest'
        with self.assertRaises(gate.GateError):
            self.valid_manifest()

    def test_soak_cannot_be_shortened_to_smoke(self):
        self.manifest['profiles']['soak']['durationSeconds'] = 60
        with self.assertRaises(gate.GateError):
            self.valid_manifest()

    def test_invalid_or_unbounded_offered_schedule_rejected(self):
        for value in (0, -1, 61, 1.5, True):
            manifest = copy.deepcopy(self.manifest)
            manifest['profiles']['stress']['workflowRate'] = value
            with self.subTest(value=value), self.assertRaises(gate.GateError):
                self.valid_manifest(manifest)

    def test_unreviewed_financial_or_receiver_payload_rejected(self):
        self.manifest['workflow']['refundMinor'] = 50000
        with self.assertRaises(gate.GateError):
            self.valid_manifest()

    def test_configuration_change_requires_new_review(self):
        self.manifest['configSha256Lf']['Dockerfile'] = '0' * 64
        with self.assertRaises(gate.GateError):
            self.valid_manifest()

    def limits(self):
        return {'apiP95Ms': 1000, 'apiP99Ms': 2000, 'maxUnexpectedErrors': 0, 'maxContractErrors': 0,
                'maxDroppedIterations': 0, 'maxPolicyLimitedRegular': 0, 'maxDrainSeconds': 300,
                'maxHeapRatio': .85, 'maxPoolPending': 10, 'maxOutboxP99Seconds': 30,
                'maxWebhookP99Seconds': 180, 'maxSoakHeapGrowthBytes': 128 * 1024 ** 2, 'maxProviderP99Seconds': .05}

    def approval(self):
        now = dt.datetime.now(dt.timezone.utc)
        calibration = {'status': 'calibrated', 'completedAt': (now - dt.timedelta(minutes=20)).isoformat(),
                       'workloadSha256': 'digest', 'imageId': self.manifest['applicationImage']}
        approval = {'status': 'APPROVED', 'approvedBy': 'owner', 'approvedAt': (now - dt.timedelta(minutes=10)).isoformat(),
                    'calibrationSha256': 'report-hash', 'workloadSha256': 'digest', 'thresholds': self.limits()}
        return approval, calibration

    def check(self, approval, calibration):
        return benchmark.check_approval(approval, calibration, 'report-hash', 'digest', self.manifest)

    def test_pending_owner_review_cannot_start_acceptance(self):
        approval, calibration = self.approval()
        approval['status'] = 'PENDING_OWNER_REVIEW'
        with self.assertRaises(gate.GateError):
            self.check(approval, calibration)

    def test_exact_post_calibration_approval_accepted(self):
        approval, calibration = self.approval()
        self.assertEqual(self.check(approval, calibration), self.limits())

    def test_changed_workload_report_or_artifact_cannot_reuse_approval(self):
        for field in ('workloadSha256', 'calibrationSha256'):
            approval, calibration = self.approval()
            approval[field] = 'other'
            with self.subTest(field=field), self.assertRaises(gate.GateError):
                self.check(approval, calibration)
        approval, calibration = self.approval()
        calibration['imageId'] = 'other'
        with self.assertRaises(gate.GateError):
            self.check(approval, calibration)

    def test_approval_cannot_predate_calibration_or_be_in_future(self):
        for offset in (-30, 30):
            approval, calibration = self.approval()
            approval['approvedAt'] = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=offset)).isoformat()
            with self.subTest(offset=offset), self.assertRaises(gate.GateError):
                self.check(approval, calibration)

    def test_incomplete_negative_boolean_or_nonfinite_thresholds_rejected(self):
        for value in (-1, True, float('nan'), float('inf')):
            approval, calibration = self.approval()
            approval['thresholds']['apiP95Ms'] = value
            with self.subTest(value=value), self.assertRaises(gate.GateError):
                self.check(approval, calibration)
        approval, calibration = self.approval()
        del approval['thresholds']['apiP95Ms']
        with self.assertRaises(gate.GateError):
            self.check(approval, calibration)

    def test_unexpected_or_contract_failures_cannot_be_waived(self):
        for key in ('maxUnexpectedErrors', 'maxContractErrors'):
            approval, calibration = self.approval()
            approval['thresholds'][key] = 1
            with self.subTest(key=key), self.assertRaises(gate.GateError):
                self.check(approval, calibration)

    def test_real_provider_bucket_bound_is_not_fabricated_exact_latency(self):
        delta = benchmark.histogram_delta({'0.005': 1, '0.05': 1, '+Inf': 1}, {'0.005': 100, '0.05': 101, '+Inf': 101})
        self.assertEqual(delta['count'], 100)
        self.assertEqual(delta['p95UpperSeconds'], .005)
        self.assertEqual(delta['p99UpperSeconds'], .005)
        self.assertIn('not exact', delta['quantileMethod'])

    def test_empty_or_infinite_tail_provider_quantile_is_not_zero(self):
        before = {'0.005': 0, '+Inf': 0}
        self.assertIsNone(benchmark.histogram_delta(before, before)['p99UpperSeconds'])
        self.assertIsNone(benchmark.histogram_delta(before, {'0.005': 0, '+Inf': 1})['p99UpperSeconds'])

    def test_provider_reset_inventory_change_or_nonmonotonicity_rejected(self):
        for before, after in (({'1': 2, '+Inf': 2}, {'1': 1, '+Inf': 2}),
                              ({'1': 0, '+Inf': 0}, {'1': 2, '+Inf': 1}),
                              ({'1': 0, '+Inf': 0}, {'2': 0, '+Inf': 0})):
            with self.subTest(after=after), self.assertRaises(gate.GateError):
                benchmark.histogram_delta(before, after)

    def test_safe_generator_projection_keeps_only_fixed_numeric_metrics(self):
        metrics = benchmark.safe_k6(b'FLOWPAY_SAFE_SUMMARY {"metrics":{"bench_requests":{"count":10}}}\n')
        self.assertEqual(metrics['bench_requests']['count'], 10)
        self.assertEqual(metrics['bench_expected_accepted']['count'], 0)
        self.assertNotIn('bench_api_ms', metrics)

    def test_raw_url_credential_arbitrary_tag_or_nonfinite_summary_rejected(self):
        for extra in ({'secret-value': {'count': 1}}, {'bench_api_ms': {'url': 2}}, {'bench_api_ms': {'max': 'private-sensitive'}},
                      {'bench_api_ms': {'max': float('nan')}}, {'bench_api_ms': {'max': True}}):
            raw = b'FLOWPAY_SAFE_SUMMARY ' + json.dumps({'metrics': {'bench_requests': {'count': 1}, **extra}}).encode()
            with self.subTest(extra=extra), self.assertRaises(gate.GateError):
                benchmark.safe_k6(raw)

    def test_missing_generator_execution_cannot_pass(self):
        for raw in (b'no summary', b'FLOWPAY_SAFE_SUMMARY {"metrics":{}}', b'FLOWPAY_SAFE_SUMMARY {"metrics":{"bench_requests":{}}}'):
            with self.assertRaises(gate.GateError):
                benchmark.safe_k6(raw)

    def accounting(self):
        return {'bench_started_workflows': {'count': 2}, 'bench_completed_workflows': {'count': 1},
                'bench_policy_limited': {'count': 1}, 'bench_completed_queries': {'count': 1},
                'bench_started_queries': {'count': 1}, 'iterations': {'count': 3},
                'dropped_iterations': {'count': 1}, 'bench_requests': {'count': 10}, 'bench_api_ms': {'count': 10}}

    def test_offered_load_accounts_for_policy_limits_and_drops_not_only_successes(self):
        profile = {'durationSeconds': 1, 'workflowRate': 2, 'workflowTimeUnitSeconds': 1, 'queryRate': 2, 'queryTimeUnitSeconds': 1}
        result = benchmark.check_offered_load(self.accounting(), profile)
        self.assertEqual(result['nominalOfferedIterations'], 4)
        self.assertEqual(result['actualOfferedIterations'], 4)
        self.assertEqual(result['droppedIterations'], 1)

    def test_aborted_workflow_query_or_missing_timing_cannot_hide(self):
        profile = {'durationSeconds': 1, 'workflowRate': 2, 'workflowTimeUnitSeconds': 1, 'queryRate': 2, 'queryTimeUnitSeconds': 1}
        for key in ('bench_completed_workflows', 'bench_completed_queries', 'bench_api_ms'):
            metrics = self.accounting()
            metrics[key]['count'] = 0
            with self.subTest(key=key), self.assertRaises(gate.GateError):
                benchmark.check_offered_load(metrics, profile)

    def test_observed_duration_boundary_arrivals_are_kept_but_not_unbounded(self):
        profile = {'durationSeconds': 1, 'workflowRate': 2, 'workflowTimeUnitSeconds': 1, 'queryRate': 2, 'queryTimeUnitSeconds': 1}
        metrics = self.accounting()
        metrics['bench_started_queries']['count'] += 1
        metrics['bench_completed_queries']['count'] += 1
        metrics['iterations']['count'] += 1
        self.assertEqual(benchmark.check_offered_load(metrics, profile)['durationBoundarySurplus'], 1)
        metrics['dropped_iterations']['count'] += 3
        with self.assertRaises(gate.GateError):
            benchmark.check_offered_load(metrics, profile)

    def test_receiver_summary_never_includes_client_credentials_or_keys(self):
        spec = importlib.util.spec_from_file_location('load_receiver', benchmark.LOAD / 'receiver.py')
        receiver = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(receiver)
        receiver.STATE.update(client={'key': 'private-fixture-marker'}, keys={'/fast': 'private-signing-marker'})
        result = json.dumps(receiver.safe_stats())
        self.assertFalse('private-fixture-marker' in result or 'private-signing-marker' in result)
        self.assertEqual(receiver.summary(list(range(1, 101)))['p95'], 95)
        self.assertEqual(receiver.summary(list(range(1, 101)))['p99'], 99)

    def test_missing_or_nonfinite_prometheus_is_not_zero(self):
        spec = importlib.util.spec_from_file_location('load_observe', benchmark.LOAD / 'observe.py')
        observe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(observe)
        for rows in ([], [{'value': [0, 'NaN']}], [{'value': [0, '+Inf']}]):
            with patch.object(observe, 'query', return_value=rows), self.assertRaises(ValueError):
                observe.scalar('fixed expression')

    def scenario(self, profile='steady'):
        return {'profile': profile, 'drainSeconds': 10, 'generator': {
            'bench_server_error': {'count': 0}, 'bench_transport_error': {'count': 0}, 'bench_contract_error': {'count': 0},
            **{'bench_' + operation + '_ms': {'p(95)': 100, 'p(99)': 200}
               for operation in ('api', 'create', 'confirm', 'refund', 'query', 'replay', 'conflict')},
            'dropped_iterations': {'count': 0}, 'bench_policy_limited': {'count': 0}},
            'observations': {'heapRatio': {'max': .5}, 'poolPending': {'max': 0},
                             'heapBytes': {'firstThirdMean': 100, 'lastThirdMean': 200}},
            'lags': {'outbox': {'p99': 1}, 'webhook': {'p99': 15}}, 'audit': {'invariants': True},
            'provider': {'payment': {'p99UpperSeconds': .005}, 'refund': {'p99UpperSeconds': .005}}}

    def test_predeclared_limits_pass_and_violation_does_not_recalibrate(self):
        scenario = self.scenario()
        limits = self.limits()
        self.assertEqual(benchmark.evaluate(scenario, limits), [])
        scenario['generator']['bench_api_ms']['p(99)'] = 3000
        self.assertEqual(benchmark.evaluate(scenario, limits), ['apiP99Ms'])
        self.assertEqual(limits, self.limits())

    def test_slow_financial_operation_is_not_hidden_by_query_dominated_aggregate(self):
        scenario = self.scenario()
        scenario['generator']['bench_confirm_ms']['p(99)'] = 3000
        self.assertEqual(benchmark.evaluate(scenario, self.limits()), ['confirmP99Ms'])

    def test_missing_or_slow_provider_bucket_bound_fails_regular_acceptance(self):
        scenario = self.scenario()
        scenario['provider']['payment']['p99UpperSeconds'] = None
        scenario['provider']['refund']['p99UpperSeconds'] = .5
        self.assertEqual(set(benchmark.evaluate(scenario, self.limits())), {'paymentProviderP99Seconds', 'refundProviderP99Seconds'})

    def test_stress_policy_limiting_is_classified_not_hidden_as_success(self):
        scenario = self.scenario('stress')
        scenario['generator']['bench_policy_limited']['count'] = 100
        scenario['generator']['dropped_iterations']['count'] = 20
        self.assertEqual(benchmark.evaluate(scenario, self.limits()), [])
        scenario['generator']['bench_server_error']['count'] = 1
        self.assertIn('unexpectedErrors', benchmark.evaluate(scenario, self.limits()))

    def test_missing_latency_or_financial_invariant_cannot_pass_acceptance(self):
        scenario = self.scenario()
        scenario['generator']['bench_api_ms'] = {}
        scenario['audit']['invariants'] = False
        self.assertEqual(set(benchmark.evaluate(scenario, self.limits())), {'apiP95Ms', 'apiP99Ms', 'financialInvariants'})

    def test_benchmark_sql_forces_server_side_read_only(self):
        runner = benchmark.Benchmark.__new__(benchmark.Benchmark)
        with patch.object(runner, 'compose', return_value=b't') as invoked:
            self.assertEqual(runner.sql('SELECT true'), b't')
        self.assertIn('PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=10000', invoked.call_args.args)

    def test_no_runtime_or_retry_override_is_added_by_load_overlay(self):
        source = (benchmark.LOAD / 'fixture.yaml').read_text()
        self.assertNotIn('\n  app:', source)
        self.assertNotIn('RETRY', source)
        self.assertNotIn('ports:', source)
