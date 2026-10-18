"""P8-T15: real, owned-only two-replica failure/recovery drills.

No cloud writes, app overrides, financial SQL writes, shortened leases/retries,
security waiver or automatic DLQ replay. Uses the unchanged verified T13 image.
"""
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

SPEC = importlib.util.spec_from_file_location('baseline', Path(__file__).resolve().parents[1] / 'load/benchmark.py')
baseline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(baseline)
staging, gate, ROOT = baseline.staging, baseline.gate, baseline.ROOT
DRILLS = ROOT / 'ops/resilience'
CASES = ('multi_instance', 'broker_outage', 'database_outage', 'worker_kill', 'stale_result',
         'slow_receiver', 'monitoring_outage', 'rolling_restart')
ALLOWED_SERVICES = {'app', 'postgres', 'rabbitmq', 'prometheus', 'grafana', 'collector', 'tempo'}


def owned_target(project, state):
    gate.require(re.fullmatch(r'flowpay-stage-[a-f0-9]{32}', project) and
                 re.fullmatch(r'[a-f0-9]{64}', state.get('Id', '')) and
                 state.get('Config', {}).get('Labels', {}).get('com.docker.compose.project') == project and
                 state['Config']['Labels'].get('com.docker.compose.service') in ALLOWED_SERVICES,
                 'refusing fault injection outside exact owned project/service')
    return state['Id']


def validate_manifest(value):
    gate.require(value.get('replicas') == 2 and value.get('cases') == list(CASES), 'two replicas and every declared drill required')
    gate.require(value.get('readinessSeconds') == 180 and value.get('recoverySeconds') == 180 and value.get('outageSeconds') == 8,
                 'reviewed finite drill bounds required; do not extend to fabricate recovery')


def validate_host(host, compose_version, manifest):
    target = manifest['host']
    gate.require(all(host.get(k) == target[k] for k in ('OSType', 'Architecture', 'NCPU', 'KernelVersion', 'ServerVersion')) and
                 compose_version == target['composeVersion'] and
                 target['minimumMemoryBytes'] <= host.get('MemTotal', 0) <= target['maximumMemoryBytes'],
                 'host outside predeclared two-replica failure-drill envelope')
    # This is not another performance acceptance. Preserve T14's exact host and
    # evidence untouched; record actual T15 MemTotal (WSL varies by a page).
    for relative, expected in gate.document(baseline.LOAD / 'manifest.json')['configSha256Lf'].items():
        path = (ROOT / relative).resolve()
        gate.require(path.is_relative_to(ROOT.resolve()) and path.is_file() and not path.is_symlink() and
                     hashlib.sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest() == expected,
                     'verified T13 runtime configuration changed')


def meter(raw, name):
    gate.require(b'jvm_memory_used_bytes' in raw, 'private application metrics missing')
    values = re.findall(rb'^' + re.escape(name.encode()) + rb'(?:\{[^\n]*\})? ([0-9.eE+-]+)$', raw, re.M)
    values = [float(v) for v in values]
    gate.require(all(math.isfinite(v) and v >= 0 for v in values), 'nonfinite/negative private counter is not evidence')
    return sum(values)  # An unregistered before-first-use counter is legitimately absent.


def workload_digest():
    digest = hashlib.sha256()
    for path in sorted(DRILLS.iterdir()):
        if path.suffix in ('.py', '.sql', '.yaml', '.json'):
            digest.update(path.name.encode() + b'\0' + path.read_bytes().replace(b'\r\n', b'\n') + b'\0')
    return digest.hexdigest()


class Drill(staging.Smoke):
    def __init__(self, evidence, work, identity, manifest):
        super().__init__('resilience', evidence, work, identity)
        self.command += ['--file', str(DRILLS / 'fixture.yaml')]
        self.manifest = manifest
        self.affected = {}

    def sql(self, statement):
        return self.compose('exec', '-T', '--env', 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=10000',
                            'postgres', 'psql', '-U', 'flowpay_stage', '-d', 'flowpay_stage', '-v', 'ON_ERROR_STOP=1', '-Atc', statement).strip()

    def wait(self, predicate, label, seconds=None):
        deadline = time.monotonic() + (seconds or self.manifest['recoverySeconds'])
        while True:
            if predicate():
                return
            gate.require(time.monotonic() < deadline, 'owned recovery deadline: ' + label)
            time.sleep(.25)

    def action(self, state, operation):
        identity = owned_target(self.project, state)
        fresh = json.loads(gate.run(['docker', 'inspect', identity])[0])[0]
        gate.require(owned_target(self.project, fresh) == identity, 'owned container identity changed')
        commands = {'pause': ['pause'], 'unpause': ['unpause'], 'kill': ['kill', '--signal=KILL'],
                    'stop': ['stop', '--time=120'], 'start': ['start']}
        gate.require(operation in commands, 'unreviewed container operation')
        self.affected[identity] = fresh
        gate.run(['docker'] + commands[operation] + [identity], timeout=150)

    def control(self, body=None, stats=False):
        # Private receiver data stays in this process, never in report/log/argv.
        code = ('import json,sys,urllib.request; b=None if sys.argv[1]=="null" else sys.argv[1].encode(); '
                'r=urllib.request.Request("http://127.0.0.1:8081/"+sys.argv[2],data=b,headers={"Content-Type":"application/json"}); '
                'v=urllib.request.urlopen(r,timeout=5).read(); print(v.decode() if v else "null")')
        raw = self.compose('exec', '-T', 'receiver', 'python', '-B', '-c', code, json.dumps(body), 'stats' if stats else '')
        return json.loads(raw)

    def probe_json(self, mode, case='fixture'):
        return json.loads(self.compose('--profile', 'checks', 'run', '--rm', '--no-deps', 'probe', mode, case))

    def async_probe(self, mode, case):
        return subprocess.Popen(self.command + ['--profile', 'checks', 'run', '--rm', '--no-deps', 'probe', mode, case],
                                cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def finish_probe(self, process, timeout=45):
        try:
            raw, _ = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            raise gate.GateError('owned asynchronous probe deadline; cleanup required') from None
        gate.require(process.returncode == 0, 'owned asynchronous probe failed; diagnostics withheld')
        return json.loads(raw)

    def healthy(self):
        def check():
            states = self.states('app')
            gate.require(len(states) == 2 and not any(s['State']['OOMKilled'] for s in states), 'two app instances required without OOM')
            return all(s['State'].get('Health', {}).get('Status') == 'healthy' and s['State']['Running'] and not s['State'].get('Paused') for s in states)
        self.wait(check, 'two replicas healthy', self.manifest['readinessSeconds'])
        self.probe_json('ready')

    def initialize(self):
        self.phase('fresh synthetic infrastructure and unchanged T13 resources')
        base = re.search(r'^FROM (.+)$', (ROOT / 'Dockerfile').read_text(), re.M)[1]
        name = 'flowpay-ci-' + uuid.uuid4().hex
        command = ['docker', 'run', '--rm', '--name', name, '--label', 'flowpay.ci.owner=' + name,
                   '--network=none', '--env', 'FLOWPAY_STAGE_DISPOSABLE=synthetic-only']
        if os.name != 'nt':
            command += ['--user', f'{os.getuid()}:{os.getgid()}']
        for source, target in ((self.secrets, '/secrets'), (self.secrets, '/cert'), (staging.STAGE / 'tests/init-secrets.sh', '/init.sh'),
                               (ROOT / 'ops/monitoring/tests/init-secrets.sh', '/helpers/base-init.sh'),
                               (ROOT / 'ops/egress/tests/init-tls.sh', '/helpers/merchant-tls.sh')):
            command += ['--mount', f'type=bind,source={source},target={target}' + (',readonly' if source.is_file() else '')]
        gate.run(command + ['--entrypoint=sh', base, '/init.sh'], timeout=90)
        staging.validate_config(json.loads(self.compose('--profile', 'checks', 'config', '--format', 'json')), self.project, fixture=True)
        self.compose('up', '--detach', 'postgres', 'rabbitmq', 'topology', 'egress', 'receiver', 'prometheus', 'grafana', 'collector', 'tempo', timeout=300)
        self.wait(lambda: not self.states('topology')[0]['State']['Running'] and
                  self.states('postgres')[0]['State'].get('Health', {}).get('Status') == 'healthy', 'topology before producers')
        gate.require(self.states('topology')[0]['State']['ExitCode'] == 0, 'topology bootstrap failed')
        self.recreate_app(replicas=2)
        self.compose('up', '--detach', '--no-deps', 'edge')
        self.probe_json('ready')
        self.probe_json('setup')

    def drain(self):
        start = time.monotonic()
        self.wait(lambda: self.sql("""SELECT
          NOT EXISTS(SELECT 1 FROM outbox_events WHERE status<>'PUBLISHED')
          AND NOT EXISTS(SELECT 1 FROM webhook_deliveries WHERE status<>'DELIVERED')
          AND NOT EXISTS(SELECT 1 FROM webhook_delivery_attempts WHERE finished_at IS NULL)
          AND (SELECT COUNT(*) FROM webhook_events)=(SELECT COUNT(*) FROM outbox_events)
          AND (SELECT COUNT(*) FROM webhook_deliveries)=2*(SELECT COUNT(*) FROM payment_intents)
          AND (SELECT COUNT(*) FROM ledger_transactions)=2*(SELECT COUNT(*) FROM payment_intents)""") == b't', 'durable drain')
        return time.monotonic() - start

    def snapshot(self):
        tables = ('payment_intents', 'payment_transactions', 'refunds', 'idempotency_records', 'ledger_transactions',
                  'ledger_entries', 'outbox_events', 'webhook_events', 'webhook_deliveries', 'webhook_delivery_attempts')
        return self.sql('SELECT jsonb_build_object(' + ','.join("'" + t + "',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM " + t + ' t)' for t in tables) + ')')

    def audit(self, expected_payments):
        for service in staging.CORE - {'topology'} | {'receiver'}:
            for value in self.states(service):
                gate.require(value['State']['Running'] and not value['State']['OOMKilled'] and not value['State'].get('Paused'), 'stopped/paused/OOM service after recovery')
        value = json.loads(self.sql((DRILLS / 'audit.sql').read_text()))
        gate.require(value['invariants'] and value['payments'] == expected_payments, 'whole-project financial/attempt audit failed')
        receiver = self.control(stats=True)
        gate.require(not receiver['invalid'] and receiver['identities'] == value['deliveries'], 'signed immutable receiver identity audit failed')
        return value

    def metrics(self, state, name):
        identity = owned_target(self.project, state)
        raw, _ = gate.run(['docker', 'exec', identity, 'curl', '--fail', '--silent', 'http://127.0.0.1:9091/actuator/prometheus'], timeout=10)
        return meter(raw, name)

    def duplicate_replay(self):
        before = self.snapshot()
        requests = self.control(stats=True)['requests']
        envelopes = json.loads(self.sql("SELECT jsonb_agg(jsonb_build_object('eventId',event_id,'eventType',event_type,'aggregateType',aggregate_type,'aggregateId',aggregate_id,'occurredAt',payload->>'occurredAt','payload',payload) ORDER BY id) FROM outbox_events"))
        gate.write_json(self.fixtures / 'duplicates.json', envelopes)
        result = self.probe_json('duplicates')
        self.drain()
        gate.require(self.snapshot() == before and self.control(stats=True)['requests'] == requests, 'duplicate redelivery changed immutable history or sent extra HTTP')
        return result

    def held_worker(self, case):
        self.probe_json('prepare', case)
        states = self.states('app')
        counts = {s['Id']: self.metrics(s, 'flowpay_webhook_delivery_attempts_total') for s in states}
        self.control({'arm': 'hold'})
        process = self.async_probe('confirm-once', case)
        try:
            self.finish_probe(process)  # Require actual source200 before pausing its process.
            self.wait(lambda: self.control()['held'], 'merchant sees first HTTP', 30)
            owners = [s for s in states if self.metrics(s, 'flowpay_webhook_delivery_attempts_total') == counts[s['Id']] + 1]
            gate.require(len(owners) == 1, 'cannot identify exact in-flight worker; no arbitrary victim')
            owner = owners[0]
            event = self.control()['selected']
            gate.require(re.fullmatch(r'evt_[A-Za-z0-9]+', event) is not None, 'invalid owned fixture event identity')
            delivery = int(self.sql("SELECT d.id FROM webhook_deliveries d JOIN webhook_events e ON e.id=d.webhook_event_id WHERE e.public_id='" + event + "'"))
            gate.require(self.sql(f'SELECT finished_at IS NULL FROM webhook_delivery_attempts WHERE delivery_id={delivery} AND attempt_no=1') == b't', 'in-flight barrier missed; no lease drill acceptance')
            start = time.monotonic()
            old_http = self.metrics(owner, 'flowpay_webhook_http_outcomes_total')
            self.action(owner, 'kill' if case == 'worker_kill' else 'pause')
            self.control({'release': True})
            self.wait(lambda: self.sql(f"SELECT status='DELIVERED' AND attempt_count=2 FROM webhook_deliveries WHERE id={delivery}") == b't', 'surviving worker lease recovery')
            old = self.sql(f'SELECT to_jsonb(a) FROM webhook_delivery_attempts a WHERE delivery_id={delivery} AND attempt_no=1')
            value = json.loads(old)
            gate.require(value['error_message'] == 'DELIVERY_LEASE_EXPIRED' and value['http_status'] is None and value['duration_ms'] is None,
                         'abandoned attempt must retain unknown HTTP outcome')
            recovery = time.monotonic() - start
            if case == 'stale_result':
                self.action(owner, 'unpause')
                self.wait(lambda: self.metrics(owner, 'flowpay_webhook_http_outcomes_total') > old_http, 'late worker HTTP result processed', 30)
                # The result counter precedes TX finalization. Allow that transaction to finish.
                time.sleep(2)
                gate.require(old == self.sql(f'SELECT to_jsonb(a) FROM webhook_delivery_attempts a WHERE delivery_id={delivery} AND attempt_no=1'), 'late finalizer rewrote abandoned attempt')
            else:
                self.action(owner, 'start')
            self.healthy()
            self.control({'arm': 'normal'})
            self.probe_json('refund', case)
            self.probe_json('replay', case)
            return {'sourceConfirmation200': True, 'survivorRecoverySeconds': recovery,
                    'attempts': 2, 'oldAttemptUnknownAndRetained': True, 'lateResultProcessedAndFenced': case == 'stale_result'}
        finally:
            if process.poll() is None:
                self.finish_probe(process)

    def run_case(self, name):
        if name == 'multi_instance':
            self.probe_json('concurrent', name)
            result = self.probe_json('replicas')
            self.drain()
            result.update(self.duplicate_replay())
            return result
        if name == 'broker_outage':
            broker = self.states('rabbitmq')[0]
            self.action(broker, 'stop')
            try:
                self.probe_json('ready')  # Readiness intentionally excludes RabbitMQ.
                self.probe_json('workflow', name)
                pending = int(self.sql("SELECT COUNT(*) FROM outbox_events WHERE status<>'PUBLISHED'"))
                gate.require(pending == 4, 'business facts must commit while broker is down')
                time.sleep(self.manifest['outageSeconds'])
            finally:
                self.action(broker, 'start')
            return {'sourceResponses': [201, 200, 201], 'unpublishedDuringOutage': pending, 'drainSeconds': self.drain()}
        if name == 'database_outage':
            before = self.snapshot()
            database = self.states('postgres')[0]
            self.action(database, 'stop')
            try:
                result = self.probe_json('database-down')
                time.sleep(self.manifest['outageSeconds'])
            finally:
                self.action(database, 'start')
            self.healthy()
            gate.require(before == self.snapshot(), 'database outage changed retained source/financial history')
            self.probe_json('workflow', name)
            return {**result, 'retainedSnapshotUnchanged': True}
        if name in ('worker_kill', 'stale_result'):
            return self.held_worker(name)
        if name == 'slow_receiver':
            self.control({'arm': 'slow'})
            self.probe_json('workflow', name)
            result = {'drainSeconds': self.drain()}
            event = self.control()['selected']
            gate.require(re.fullmatch(r'evt_[A-Za-z0-9]+', event) is not None, 'owned slow event identity missing')
            gate.require(self.sql("SELECT d.attempt_count=2 AND a.http_status IS NULL AND a.error_message='REQUEST_TIMEOUT' FROM webhook_deliveries d JOIN webhook_events e ON e.id=d.webhook_event_id JOIN webhook_delivery_attempts a ON a.delivery_id=d.id AND a.attempt_no=1 WHERE e.public_id='" + event + "'") == b't', 'slow HTTP must time out and retain retry history')
            self.control({'arm': 'normal'})
            return {**result, 'timeoutRetainedThenRetryDelivered': True}
        if name == 'monitoring_outage':
            targets = [self.states(s)[0] for s in ('prometheus', 'grafana', 'collector', 'tempo')]
            before = self.snapshot()
            for target in targets:
                self.action(target, 'stop')
            try:
                listeners = self.probe_json('monitoring-down')
                self.probe_json('workflow', name)
                self.drain()
                time.sleep(self.manifest['outageSeconds'])
            finally:
                for target in targets:
                    self.action(target, 'start')
            gate.require(before != self.snapshot(), 'monitoring outage did not exercise committed source work')
            return {**self.probe_json('replicas'), **listeners, 'businessSucceededWithoutTelemetry': True}
        if name == 'rolling_restart':
            self.control({'trafficStop': False})
            process = self.async_probe('traffic', name)
            rows = []
            try:
                for index, state in enumerate(self.states('app')):
                    start = time.monotonic()
                    self.action(state, 'stop')
                    stopped = json.loads(gate.run(['docker', 'inspect', owned_target(self.project, state)])[0])[0]
                    gate.require(stopped['State']['ExitCode'] in (0, 143) and not stopped['State']['OOMKilled'], 'rolling stop was forced/OOM, not graceful')
                    self.probe_json('ready')  # Survivor must serve through the same edge.
                    survivor = self.probe_json('available-query')
                    self.action(state, 'start')
                    self.healthy()
                    self.probe_json('workflow', name + str(index))
                    rows.append({'restartSeconds': time.monotonic() - start, 'gracefulExit': stopped['State']['ExitCode'], **survivor})
            finally:
                self.control({'trafficStop': True})
                traffic = self.finish_probe(process)
            return {'replicasRestarted': rows, 'queryTraffic': traffic}
        raise gate.GateError('unknown drill')

    def cleanup(self):
        for identity, state in self.affected.items():
            raw, code = gate.run(['docker', 'inspect', identity], allowed=(0, 1))
            if code == 0 and json.loads(raw)[0]['State'].get('Paused'):
                self.action(state, 'unpause')
        self.compose('--profile', 'checks', 'down', '--volumes', '--remove-orphans', timeout=240)
        staging.assert_empty_project(self.project)
        self.evidence['ownedSyntheticCleanup'] = 'passed'


def run(evidence):
    manifest = gate.document(DRILLS / 'manifest.json')
    validate_manifest(manifest)
    identity = staging.image.image_identity()
    gate.require(identity == manifest['applicationImage'] and gate.sha256(staging.image.verified_jar()) == manifest['jarSha256'], 'unchanged verified T13 image/JAR required')
    load = gate.document(gate.SAFE / 'load-acceptance.json')
    gate.require(load.get('status') == 'accepted' and load.get('imageId') == identity and
                 gate.sha256(gate.SAFE / 'load-acceptance.json') == manifest['loadAcceptanceSha256'] and
                 load.get('workloadSha256') == baseline.workload_digest(), 'matching T14 acceptance required, not a synthetic pass')
    host = json.loads(gate.run(['docker', 'info', '--format', '{{json .}}'])[0])
    compose_version = gate.run(['docker', 'compose', 'version', '--short'])[0].decode().strip()
    validate_host(host, compose_version, manifest)
    gate.require(not gate.run(['docker', 'ps', '--quiet'])[0].strip(), 'host has active containers; do not stop/adopt unrelated workloads')
    digest = workload_digest()
    evidence.update(imageId=identity, jarSha256=manifest['jarSha256'], workloadSha256=digest,
                    loadAcceptanceSha256=manifest['loadAcceptanceSha256'], replicas=2,
                    host={k: host[k] for k in ('OSType', 'Architecture', 'NCPU', 'MemTotal', 'KernelVersion', 'ServerVersion')},
                    composeVersion=compose_version, cases=[], securityRelease='BLOCKED; no security waiver')
    keys = ('FLOWPAY_IMAGE', 'FLOWPAY_TEST_CLASSES', 'STAGING_SECRETS_DIR', 'STAGING_FIXTURE_DIR', 'STAGING_HTTPS_PORT',
            'STAGING_TEST_OUTCOME', 'STAGING_TEST_WORKERS', 'STAGING_TEST_JWT_LOCATION', 'STAGING_PROTECTED_TARGETS')
    previous = {k: os.environ.get(k) for k in keys}
    try:
        with tempfile.TemporaryDirectory(prefix='flowpay-resilience-') as directory:
            drill = Drill(evidence, Path(directory), identity, manifest)
            os.environ.update(FLOWPAY_IMAGE=identity, FLOWPAY_TEST_CLASSES=str(ROOT / 'target/test-classes'),
                              STAGING_SECRETS_DIR=str(drill.secrets), STAGING_FIXTURE_DIR=str(drill.fixtures),
                              STAGING_HTTPS_PORT='0', STAGING_TEST_OUTCOME='SUCCESS', STAGING_TEST_WORKERS='true',
                              STAGING_TEST_JWT_LOCATION='file:/run/secrets/jwt-private.pem', STAGING_PROTECTED_TARGETS='[]')
            staging.assert_empty_project(drill.project)
            try:
                drill.initialize()
                payments = 0
                for name in CASES:
                    drill.phase(name)
                    start = time.monotonic()
                    result = drill.run_case(name)
                    payments += 2 if name == 'rolling_restart' else 1
                    drain = drill.drain()
                    audit = drill.audit(payments)
                    evidence['cases'].append({'name': name, 'status': 'passed', 'totalSeconds': time.monotonic() - start,
                                              'postCaseDrainSeconds': drain, 'observed': result, 'audit': audit})
            finally:
                drill.cleanup()
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
    gate.require(workload_digest() == digest, 'drill changed during measurement; rerun final unchanged harness')
    evidence.update(status='passed', completedAt=dt.datetime.now(dt.timezone.utc).isoformat())


def main():
    path = gate.SAFE / 'resilience.json'
    path.unlink(missing_ok=True)
    evidence = {'status': 'incomplete', 'timestamp': dt.datetime.now(dt.timezone.utc).isoformat()}
    try:
        run(evidence)
    except gate.GateError as error:
        evidence.update(status='failed', reason=str(error))
    except Exception:
        evidence.update(status='failed', reason='owned resilience drill failed; raw credentials/body/SQL/diagnostics withheld')
    gate.write_json(path, evidence)
    print('resilience: ' + evidence['status'] + ' (safe evidence: target/ci-safe/resilience.json)')
    return 0 if evidence['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
