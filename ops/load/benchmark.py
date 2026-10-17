"""P8-T14: synthetic-only calibration and separately owner-approved acceptance.

Uses the verified T13 artifact; never changes app configuration, retry schedules,
schema, public exposure, security exceptions or development data.
"""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid

SPEC = importlib.util.spec_from_file_location('staging', Path(__file__).resolve().parents[1] / 'staging/staging.py')
staging = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(staging)
gate = staging.gate
ROOT = gate.ROOT
LOAD = ROOT / 'ops/load'


def workload_digest():
    digest = hashlib.sha256()
    # Approval/results/docs are not executable workload inputs. Normalize checkout LF.
    for path in sorted(LOAD.iterdir()):
        if path.suffix in ('.py', '.js', '.sql', '.yaml') or path.name == 'manifest.json':
            digest.update(path.name.encode() + b'\0' + path.read_bytes().replace(b'\r\n', b'\n') + b'\0')
    return digest.hexdigest()


def validate_manifest(manifest, host, compose_version):
    target = manifest['target']
    gate.require(all(host.get(key) == target[key] for key in ('OSType', 'Architecture', 'NCPU', 'MemTotal', 'KernelVersion', 'ServerVersion')),
                 'frozen benchmark host differs; review a new manifest, do not compare silently')
    gate.require(compose_version == target['composeVersion'] and target['appReplicas'] == 1, 'frozen Compose/replica identity differs')
    gate.require(re.fullmatch(r'grafana/k6:2\.0\.0@sha256:[a-f0-9]{64}', manifest['generatorImage']), 'pinned reviewed generator required')
    gate.require(manifest['workflow'] == {'amountMinor': 100000, 'refundMinor': 1000, 'currency': 'VND',
                                         'requestsPerCompletedWorkflow': 9, 'subscriberFanout': 3,
                                         'slowReceiverSeconds': .75, 'flakyFailuresPerDelivery': 1}, 'unreviewed workload contract')
    gate.require(manifest['profiles']['soak']['durationSeconds'] >= 1800, 'soak cannot be silently shortened')
    for name, profile in manifest['profiles'].items():
        gate.require(re.fullmatch(r'[a-z_]+', name) and 10 <= profile['durationSeconds'] <= 3600,
                     'bounded frozen profile required')
        gate.require(all(isinstance(profile[key], int) and not isinstance(profile[key], bool) and 1 <= profile[key] <= 60 for key in
                         ('workflowRate', 'workflowTimeUnitSeconds', 'queryRate', 'queryTimeUnitSeconds')), 'invalid offered-load schedule')
    for relative, expected in manifest['configSha256Lf'].items():
        path = (ROOT / relative).resolve()
        gate.require(path.is_relative_to(ROOT.resolve()) and path.is_file() and not path.is_symlink(), 'invalid frozen config path')
        gate.require(hashlib.sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest() == expected,
                     'T13 configuration changed; calibration/approval must be reviewed again')


def check_approval(approval, calibration, calibration_hash, digest, manifest):
    gate.require(approval.get('status') == 'APPROVED' and approval.get('approvedBy') == 'owner',
                 'acceptance blocked: owner must approve numeric thresholds after calibration')
    approved = dt.datetime.fromisoformat(approval['approvedAt'])
    calibrated = dt.datetime.fromisoformat(calibration['completedAt'])
    gate.require(calibrated < approved <= dt.datetime.now(dt.timezone.utc), 'approval must follow calibration, before acceptance')
    gate.require(calibration.get('status') == 'calibrated' and calibration.get('workloadSha256') == digest and
                 approval.get('workloadSha256') == digest and approval.get('calibrationSha256') == calibration_hash,
                 'acceptance blocked: calibration/approval does not match the frozen workload')
    gate.require(calibration.get('imageId') == manifest['applicationImage'], 'acceptance blocked: calibrated artifact differs')
    limits = approval.get('thresholds')
    required = {'apiP95Ms', 'apiP99Ms', 'maxUnexpectedErrors', 'maxContractErrors', 'maxDroppedIterations',
                'maxPolicyLimitedRegular', 'maxDrainSeconds', 'maxHeapRatio', 'maxPoolPending', 'maxOutboxP99Seconds',
                'maxWebhookP99Seconds', 'maxSoakHeapGrowthBytes', 'maxProviderP99Seconds'}
    gate.require(isinstance(limits, dict) and set(limits) == required and
                 all(isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0 for value in limits.values()),
                 'acceptance blocked: complete finite numeric thresholds required')
    gate.require(limits['apiP99Ms'] >= limits['apiP95Ms'] > 0 and 0 < limits['maxHeapRatio'] <= 1 and
                 limits['maxDrainSeconds'] <= manifest['hardDrainSeconds'], 'acceptance threshold bounds invalid')
    gate.require(limits['maxUnexpectedErrors'] == limits['maxContractErrors'] == 0, 'financial/contract failures cannot be waived')
    return limits


def histogram_delta(before, after):
    gate.require(set(before) == set(after) and '+Inf' in after, 'provider bucket inventory differs')
    buckets = sorted((float(bound), after[bound] - before[bound]) for bound in after)
    gate.require(all(math.isfinite(value) and value >= 0 for _, value in buckets), 'provider counter reset/invalid sample')
    gate.require(all(buckets[index][1] <= buckets[index + 1][1] for index in range(len(buckets) - 1)), 'non-monotone provider buckets')
    count = buckets[-1][1]
    result = {'count': count, 'quantileMethod': 'upper fixed-bucket bound, not exact latency'}
    for name, quantile in (('p95UpperSeconds', .95), ('p99UpperSeconds', .99)):
        upper = next((bound for bound, value in buckets if value >= count * quantile), None) if count else None
        result[name] = upper if upper is not None and math.isfinite(upper) else None
    return result


def safe_k6(raw):
    match = re.search(rb'FLOWPAY_SAFE_SUMMARY (\{[^\r\n]+\})', raw)
    gate.require(match is not None, 'generator summary missing; raw diagnostics withheld')
    source = json.loads(match[1])
    metrics = source.get('metrics', {})
    gate.require(bool(metrics) and 'bench_requests' in metrics, 'generator executed no measured requests')
    allowed_values = {'count', 'rate', 'min', 'avg', 'med', 'max', 'p(95)', 'p(99)', 'value'}
    allowed_names = {'dropped_iterations', 'iterations', 'vus', 'vus_max', 'bench_started_workflows', 'bench_completed_workflows',
                     'bench_completed_queries', 'bench_started_queries', 'bench_requests'}
    allowed_names |= {'bench_' + name for name in ('expected_success', 'expected_conflict', 'expected_accepted',
                      'expected_business_failure', 'policy_limited', 'server_error', 'transport_error', 'contract_error')}
    allowed_names |= {'bench_' + name + '_ms' for name in ('api', 'create', 'confirm', 'refund', 'query', 'replay', 'conflict')}
    gate.require(set(metrics) <= allowed_names, 'unsafe generator metric name')
    for values in metrics.values():
        gate.require(isinstance(values, dict) and set(values) <= allowed_values and
                     all(isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0
                         for value in values.values()), 'unsafe generator numeric projection')
    gate.require(metrics['bench_requests'].get('count', 0) > 0, 'generator executed no measured requests')
    # Absent zero-valued counters are explicitly zero; missing latency is NOT zero.
    for name in allowed_names - {name for name in allowed_names if name.endswith('_ms')} - {'vus', 'vus_max'}:
        metrics.setdefault(name, {'count': 0})
    return metrics


def check_offered_load(metrics, profile):
    offered = profile['durationSeconds'] * (profile['workflowRate'] / profile['workflowTimeUnitSeconds']
                                           + profile['queryRate'] / profile['queryTimeUnitSeconds'])
    started = metrics['bench_started_workflows']['count']
    completed = metrics['bench_completed_workflows']['count']
    queries = metrics['bench_completed_queries']['count']
    started_queries = metrics['bench_started_queries']['count']
    limited = metrics['bench_policy_limited']['count']
    dropped = metrics['dropped_iterations']['count']
    gate.require(started == completed + limited, 'unaccounted aborted/partial workflow, not an expected policy limit')
    gate.require(started_queries == queries and metrics['iterations']['count'] == started + started_queries,
                 'unaccounted interrupted query/iteration')
    actual = started + started_queries + dropped
    # Native k6 2.0 may dispatch the duration-endpoint arrival for either of the
    # two executors. Keep the real counts; never discard those observed calls.
    gate.require(offered <= actual <= offered + 2, 'generator did not account for frozen offered load')
    gate.require(metrics.get('bench_api_ms', {}).get('count', 0) == metrics['bench_requests']['count'], 'API timing sample loss')
    return {'nominalOfferedIterations': int(offered), 'actualOfferedIterations': int(actual),
            'durationBoundarySurplus': int(actual - offered), 'startedWorkflows': int(started), 'completedWorkflows': int(completed),
            'completedQueries': int(queries), 'policyLimitedWorkflows': int(limited), 'droppedIterations': int(dropped)}


def evaluate(scenario, limits):
    metrics, observations = scenario['generator'], scenario['observations']
    violations = []
    def check(value, limit, label):
        if value is None or not math.isfinite(value) or value > limit:
            violations.append(label)
    check(sum(metrics['bench_' + name]['count'] for name in ('server_error', 'transport_error')), limits['maxUnexpectedErrors'], 'unexpectedErrors')
    check(metrics['bench_contract_error']['count'], limits['maxContractErrors'], 'contractErrors')
    stress = scenario['profile'] == 'stress'
    if scenario['profile'] in ('steady', 'peak', 'soak'):
        for operation in ('api', 'create', 'confirm', 'refund', 'query', 'replay', 'conflict'):
            trend = metrics.get('bench_' + operation + '_ms', {})
            check(trend.get('p(95)'), limits['apiP95Ms'], operation + 'P95Ms')
            check(trend.get('p(99)'), limits['apiP99Ms'], operation + 'P99Ms')
        for provider in ('payment', 'refund'):
            check(scenario['provider'][provider]['p99UpperSeconds'], limits['maxProviderP99Seconds'], provider + 'ProviderP99Seconds')
    if not stress:
        check(metrics['dropped_iterations']['count'], limits['maxDroppedIterations'], 'droppedIterations')
        check(metrics['bench_policy_limited']['count'], limits['maxPolicyLimitedRegular'], 'regularPolicyLimited')
    check(scenario['drainSeconds'], limits['maxDrainSeconds'], 'drainSeconds')
    check(observations['heapRatio']['max'], limits['maxHeapRatio'], 'heapRatio')
    check(observations['poolPending']['max'], limits['maxPoolPending'], 'poolPending')
    check(scenario['lags']['outbox']['p99'], limits['maxOutboxP99Seconds'], 'outboxP99Seconds')
    check(scenario['lags']['webhook']['p99'], limits['maxWebhookP99Seconds'], 'webhookP99Seconds')
    if scenario['profile'] == 'soak':
        heap = observations['heapBytes']
        check(max(0, heap['lastThirdMean'] - heap['firstThirdMean']), limits['maxSoakHeapGrowthBytes'], 'soakHeapGrowthBytes')
    if not scenario['audit']['invariants']:
        violations.append('financialInvariants')
    return violations


class Benchmark(staging.Smoke):
    def __init__(self, evidence, work, identity, manifest):
        super().__init__('benchmark', evidence, work, identity)
        self.command += ['--file', str(LOAD / 'fixture.yaml')]
        self.manifest = manifest

    def sql(self, statement):
        return self.compose('exec', '-T', '--env', 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=10000',
                            'postgres', 'psql', '-U', 'flowpay_stage', '-d', 'flowpay_stage', '-v', 'ON_ERROR_STOP=1', '-Atc', statement).strip()

    def observe(self, mode, *arguments):
        raw = self.compose('--profile', 'checks', 'run', '--rm', '--no-deps', '--entrypoint', 'python', 'probe',
                           '-B', '/load/observe.py', mode, *map(str, arguments), timeout=180)
        return json.loads(raw)

    def generator(self, profile, bootstrap=False):
        arguments = ['--profile', 'checks', 'run', '--rm', '--no-deps', '--env', 'BENCH_PROFILE=' + profile]
        if bootstrap:
            arguments += ['--env', 'BENCH_BOOTSTRAP=true']
        timeout = self.manifest['profiles'][profile]['durationSeconds'] + 120
        try:
            result = subprocess.run(self.command + arguments + ['load', 'run', '--quiet', '/load/workload.js'],
                                    cwd=ROOT, capture_output=True, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise gate.GateError('owned generator unavailable/timed out') from None
        if result.returncode:
            numeric = re.search(rb'owned fixture status expected \d+, got \d+', result.stderr)
            location = re.search(rb'/load/workload.js:(\d+)', result.stderr)
            label = numeric[0].decode() if numeric else 'owned generator failed'
            if location:
                label += '; fixed workload line ' + location[1].decode()
            raise gate.GateError(label)
        return safe_k6(result.stdout)

    def initialize(self):
        self.phase('synthetic bootstrap; unchanged T13 runtime/policies')
        base = re.search(r'^FROM (.+)$', (ROOT / 'Dockerfile').read_text(), re.M)[1]
        name = 'flowpay-ci-' + uuid.uuid4().hex
        user = ['--user', f'{os.getuid()}:{os.getgid()}'] if os.name != 'nt' else []
        command = ['docker', 'run', '--rm', '--name', name, '--label', 'flowpay.ci.owner=' + name,
                   '--network=none', '--env', 'FLOWPAY_STAGE_DISPOSABLE=synthetic-only'] + user
        for source, target in ((self.secrets, '/secrets'), (self.secrets, '/cert'),
                               (staging.STAGE / 'tests/init-secrets.sh', '/init.sh'),
                               (ROOT / 'ops/monitoring/tests/init-secrets.sh', '/helpers/base-init.sh'),
                               (ROOT / 'ops/egress/tests/init-tls.sh', '/helpers/merchant-tls.sh')):
            command += ['--mount', f'type=bind,source={source},target={target}' + (',readonly' if source.is_file() else '')]
        gate.run(command + ['--entrypoint=sh', base, '/init.sh'], timeout=90)
        config = json.loads(self.compose('--profile', 'checks', 'config', '--format', 'json'))
        load = config['services'].pop('load')
        staging.validate_config(config, self.project, fixture=True)
        gate.require(load['image'] == self.manifest['generatorImage'] and load.get('read_only') is True
                     and load.get('cap_drop') == ['ALL'] and not load.get('ports') and set(load['networks']) == {'front', 'webhook'}
                     and int(load['mem_limit']) == self.manifest['generatorMemoryMiB'] * 1024 ** 2
                     and float(load['cpus']) == self.manifest['generatorCpu'], 'unreviewed generator sandbox/resources/network')
        gate.require(set(load['environment']) == {'SSL_CERT_FILE', 'K6_NO_USAGE_REPORT', 'K6_NO_COLOR', 'K6_WEB_DASHBOARD', 'K6_AUTO_EXTENSION_RESOLUTION'},
                     'unreviewed generator environment')
        self.compose('up', '--detach', 'postgres', 'rabbitmq', 'topology', 'egress', 'receiver', 'prometheus', 'grafana', 'collector', 'tempo', timeout=300)
        deadline = time.monotonic() + 150
        while self.states('topology')[0]['State']['Running'] or self.states('postgres')[0]['State'].get('Health', {}).get('Status') != 'healthy':
            gate.require(time.monotonic() < deadline, 'benchmark infrastructure bootstrap deadline')
            time.sleep(1)
        gate.require(self.states('topology')[0]['State']['ExitCode'] == 0, 'topology must complete before producers')
        self.phase('benchmark app readiness with frozen resources')
        self.recreate_app()
        self.compose('up', '--detach', '--no-deps', 'edge')
        self.probe('ready')
        self.phase('k6 low-rate seed and protocol sanity')
        seed = self.generator('sanity', bootstrap=True)
        gate.require(seed['bench_completed_workflows']['count'] == 1, 'seed sanity workflow failed')
        self.drain()
        self.audit_finance()

    def drain(self):
        start = time.monotonic()
        statement = """SELECT NOT EXISTS(SELECT 1 FROM outbox_events WHERE status<>'PUBLISHED')
          AND NOT EXISTS(SELECT 1 FROM webhook_deliveries WHERE status<>'DELIVERED')
          AND NOT EXISTS(SELECT 1 FROM webhook_delivery_attempts WHERE finished_at IS NULL)
          AND (SELECT COUNT(*) FROM webhook_events)=(SELECT COUNT(*) FROM outbox_events)
          AND (SELECT COUNT(*) FROM webhook_deliveries)=3*(SELECT COUNT(*) FROM webhook_events)
          AND (SELECT COUNT(*) FROM ledger_transactions)=(SELECT COUNT(*) FROM payment_transactions WHERE status='SUCCEEDED')
            +(SELECT COUNT(*) FROM refunds WHERE status='SUCCEEDED')"""
        while self.sql(statement) != b't':
            gate.require(time.monotonic() - start < self.manifest['hardDrainSeconds'], 'hard benchmark drain bound exceeded; no retry policy reset')
            time.sleep(2)
        return time.monotonic() - start

    def audit_finance(self):
        for service in staging.CORE - {'topology'} | {'receiver'}:
            for item in self.states(service):
                state = item['State']
                gate.require(state['Running'] and not state.get('OOMKilled'), 'benchmark service stopped/OOM: ' + service)
        value = json.loads(self.sql((LOAD / 'audit.sql').read_text()))
        gate.require(value.get('invariants') is True, 'financial/identity/retry invariant audit failed')
        receiver = self.observe('receiver')
        gate.require(not receiver['invalid'] and not receiver['overflow'] and receiver['identities'] == value['deliveries'],
                     'receiver identity/signature/bounded-capacity audit failed')
        return value

    def scenario(self, name, limits=None):
        self.phase('measuring ' + name + '; no acceptance threshold movement')
        self.observe('phase', name)
        ids = json.loads(self.sql("SELECT jsonb_build_object('outbox',COALESCE((SELECT MAX(id) FROM outbox_events),0),'event',COALESCE((SELECT MAX(id) FROM webhook_events),0))"))
        before = self.observe('histograms', time.time())
        start = time.time()
        metrics = self.generator(name)
        end = time.time()
        self.evidence['currentScenario'] = {'profile': name, 'durationObservedSeconds': end - start, 'generator': metrics}
        accounting = check_offered_load(metrics, self.manifest['profiles'][name])
        drain = self.drain()
        after = self.observe('histograms', end)
        lags = json.loads(self.sql(f"""SELECT jsonb_build_object(
          'outbox',(SELECT jsonb_build_object('count',COUNT(*),'p95',percentile_cont(.95) WITHIN GROUP(ORDER BY EXTRACT(EPOCH FROM(published_at-created_at))),
            'p99',percentile_cont(.99) WITHIN GROUP(ORDER BY EXTRACT(EPOCH FROM(published_at-created_at)))) FROM outbox_events WHERE id>{int(ids['outbox'])}),
          'webhook',(SELECT jsonb_build_object('count',COUNT(*),'p95',percentile_cont(.95) WITHIN GROUP(ORDER BY EXTRACT(EPOCH FROM(d.delivered_at-e.occurred_at))),
            'p99',percentile_cont(.99) WITHIN GROUP(ORDER BY EXTRACT(EPOCH FROM(d.delivered_at-e.occurred_at))))
            FROM webhook_deliveries d JOIN webhook_events e ON e.id=d.webhook_event_id WHERE e.id>{int(ids['event'])}))"""))
        value = {'profile': name, 'startedAt': start, 'durationObservedSeconds': end - start,
                 'drainSeconds': drain, 'generator': metrics, 'lags': lags,
                 'offeredLoadAccounting': accounting,
                 'provider': {kind: histogram_delta(before[kind], after[kind]) for kind in ('payment', 'refund')},
                 'observations': self.observe('ranges', start, end), 'audit': self.audit_finance(),
                 'receiver': self.observe('receiver')['phases'].get(name, {})}
        gate.require(value['observations']['scrapeUp']['min'] == 1, 'missing app scrape during benchmark')
        gate.require(all(value['observations'][name]['max'] <= 120 for name in ('outboxSampleAgeSeconds', 'webhookSampleAgeSeconds')),
                     'stale durable gauges are not benchmark acceptance')
        gate.require(all(metrics['bench_' + kind]['count'] == 0 for kind in ('contract_error', 'server_error', 'transport_error')),
                     'unexpected HTTP/contract failures; calibration is not a pass')
        value['violations'] = [] if limits is None else evaluate(value, limits)
        return value


def run(evidence, mode):
    manifest = gate.document(LOAD / 'manifest.json')
    identity = staging.image.image_identity()
    gate.require(identity == manifest['applicationImage'] and gate.sha256(staging.image.verified_jar()) == manifest['jarSha256'], 'use the frozen verified T13 artifact')
    stage_report = gate.document(gate.SAFE / 'staging.json')
    gate.require(stage_report.get('status') == 'passed' and stage_report.get('imageId') == identity and
                 set(stage_report.get('cases', {})) == {'fresh', 'phase7'}, 'matching T13 staging evidence required')
    raw, _ = gate.run(['docker', 'info', '--format', '{{json .}}'])
    host = json.loads(raw)
    raw, _ = gate.run(['docker', 'compose', 'version', '--short'])
    compose_version = raw.decode().strip()
    validate_manifest(manifest, host, compose_version)
    digest = workload_digest()
    evidence.update(imageId=identity, jarSha256=manifest['jarSha256'], workloadSha256=digest, host=manifest['target'],
                    generatorImage=manifest['generatorImage'], mode=mode, scenarios=[],
                    securityRelease='BLOCKED; synthetic benchmark is not release clearance')
    limits = None
    if mode == 'accept':
        calibration_path = gate.SAFE / 'load-calibration.json'
        limits = check_approval(gate.document(LOAD / 'acceptance.json'), gate.document(calibration_path),
                                gate.sha256(calibration_path), digest, manifest)
        evidence['approvedThresholds'] = limits
    raw, _ = gate.run(['docker', 'ps', '--quiet'])
    gate.require(not raw.strip(), 'benchmark host has other active containers; do not stop or adopt unrelated workloads')
    variables = ('FLOWPAY_IMAGE', 'FLOWPAY_TEST_CLASSES', 'STAGING_SECRETS_DIR', 'STAGING_FIXTURE_DIR', 'STAGING_HTTPS_PORT',
                 'STAGING_TEST_OUTCOME', 'STAGING_TEST_WORKERS', 'STAGING_TEST_JWT_LOCATION', 'STAGING_PROTECTED_TARGETS')
    previous = {key: os.environ.get(key) for key in variables}
    try:
        with tempfile.TemporaryDirectory(prefix='flowpay-load-') as directory:
            context = Benchmark(evidence, Path(directory), identity, manifest)
            os.environ.update(FLOWPAY_IMAGE=identity, FLOWPAY_TEST_CLASSES=str(ROOT / 'target/test-classes'),
                              STAGING_SECRETS_DIR=str(context.secrets), STAGING_FIXTURE_DIR=str(context.fixtures),
                              STAGING_HTTPS_PORT='0', STAGING_TEST_OUTCOME='SUCCESS', STAGING_TEST_WORKERS='true',
                              STAGING_TEST_JWT_LOCATION='file:/run/secrets/jwt-private.pem', STAGING_PROTECTED_TARGETS='[]')
            staging.assert_empty_project(context.project)
            try:
                context.initialize()
                for name in manifest['calibrationProfiles' if mode == 'calibrate' else 'acceptanceProfiles']:
                    result = context.scenario(name, limits)
                    evidence['scenarios'].append(result)
                    evidence.pop('currentScenario', None)
                    gate.require(not result['violations'], 'predeclared acceptance threshold failed; do not move it')
            finally:
                context.compose('--profile', 'checks', 'down', '--volumes', '--remove-orphans', timeout=240)
                staging.assert_empty_project(context.project)
                evidence['ownedSyntheticCleanup'] = 'passed'
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
    evidence['completedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
    gate.require(workload_digest() == digest, 'workload changed during measurement; rerun calibration, do not reuse approval')
    evidence['status'] = 'calibrated' if mode == 'calibrate' else 'accepted'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('calibrate', 'accept'))
    mode = parser.parse_args().mode
    path = gate.SAFE / ('load-calibration.json' if mode == 'calibrate' else 'load-acceptance.json')
    path.unlink(missing_ok=True)
    evidence = {'status': 'incomplete', 'timestamp': dt.datetime.now(dt.timezone.utc).isoformat()}
    try:
        run(evidence, mode)
    except gate.GateError as error:
        evidence.update(status='failed', reason=str(error))
    except Exception:
        evidence.update(status='failed', reason='benchmark failed; raw credentials/HTTP/SQL/exception diagnostics withheld')
    gate.write_json(path, evidence)
    print('load-' + mode + ': ' + evidence['status'] + ' (safe evidence: ' + path.relative_to(ROOT).as_posix() + ')')
    return 0 if evidence['status'] in ('calibrated', 'accepted') else 1


if __name__ == '__main__':
    raise SystemExit(main())
