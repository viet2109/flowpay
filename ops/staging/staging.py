"""P8-T13: guarded release preflight and synthetic-only isolated staging smoke.

No cloud/registry writes, real merchant calls, .env loading, secret waivers or
generic security bypass. The smoke command generates its own disposable keys.
"""
import argparse
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

SPEC = importlib.util.spec_from_file_location('image', Path(__file__).resolve().parents[1] / 'container/image.py')
image = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(image)
gate = image.gate
ROOT = gate.ROOT
STAGE = ROOT / 'ops/staging'
CORE = {'postgres', 'rabbitmq', 'topology', 'app', 'edge', 'egress', 'prometheus', 'grafana', 'collector', 'tempo'}
PYTHON = 'python:3.13.12-slim@sha256:f1927c75e81efd1e091dbd64b6c0ecaa5630b38635a3d1c04034ac636e1f94c8'


def validate_config(config, project, fixture=False):
    services = config['services']
    extras = {'receiver', 'seed', 'probe', 'public'} if fixture else set()
    gate.require(set(services) == CORE | extras, 'staging service inventory differs from reviewed scope')
    expected_networks = {'app': {'ingress', 'data', 'observe', 'webhook'}, 'edge': {'front', 'ingress'},
                         'postgres': {'data'}, 'rabbitmq': {'data', 'observe'}, 'topology': {'data'},
                         'egress': {'webhook', 'outbound'} | ({'destinations'} if fixture else set()),
                         'prometheus': {'observe'}, 'grafana': {'observe'}, 'collector': {'observe'}, 'tempo': {'observe'}}
    if fixture:
        expected_networks.update(receiver={'webhook', 'destinations'}, seed={'data'},
                                 probe={'ingress', 'data', 'observe', 'webhook'}, public={'front'})
    for name, service in services.items():
        gate.require('container_name' not in service and not service.get('privileged') and not service.get('network_mode'), 'unsafe staging identity/network mode')
        gate.require(int(service.get('mem_limit', 0)) > 0 and float(service.get('cpus', 0)) > 0, 'finite staging budgets required')
        reference = service['image']
        gate.require(bool(re.fullmatch(r'sha256:[a-f0-9]{64}|[^@]+:[^@:]+@sha256:[a-f0-9]{64}', reference)), 'immutable staging image required')
        ports = service.get('ports', [])
        if name == 'edge':
            gate.require(len(ports) == 1 and ports[0]['host_ip'] == '127.0.0.1' and ports[0]['target'] == 8443, 'only loopback TLS ingress may publish')
        else:
            gate.require(not ports, 'private service must not publish ports')
        if name in expected_networks:
            gate.require(set(service['networks']) == expected_networks[name], 'staging network membership differs from reviewed topology')
        for mount in service.get('volumes', []):
            gate.require('docker.sock' not in str(mount) and Path(mount.get('source', '')).name != '.env', 'host credentials/socket mount forbidden')
            gate.require(mount.get('type') != 'bind' or mount.get('read_only') is True, 'staging host binds must be read-only')
    for name in ('ingress', 'data', 'observe', 'webhook'):
        gate.require(config['networks'][name].get('internal') is True and config['networks'][name].get('enable_ipv6') is True, 'private dual-stack networks required')
    if fixture:
        gate.require(config['networks']['outbound'].get('internal') is True and config['networks']['destinations'].get('internal') is True,
                     'synthetic fixture must not route to external merchants')
    for kind in ('volumes', 'networks'):
        for value in config[kind].values():
            gate.require(not value.get('external') and value['name'].startswith(project + '_'), 'state/networks must be project-owned, not shared or development resources')
    app = services['app']
    gate.require(app.get('read_only') is True and app.get('cap_drop') == ['ALL'] and app.get('pids_limit') == 256, 'app sandbox weakened')
    gate.require(app['environment']['SPRING_PROFILES_ACTIVE'] == 'prod' and app['environment']['WEBHOOK_EGRESS_PROXY_HOST'] == 'egress', 'production/proxy policy required')
    gate.require('RABBITMQ_ADMIN_PASSWORD' not in {item['source'] for item in app['secrets']}, 'application must not receive bootstrap administrator credential')
    gate.require(app['depends_on']['topology']['condition'] == 'service_completed_successfully', 'topology must precede producers')
    gate.require(app['environment']['RABBITMQ_USERNAME'] == 'flowpay_stage', 'non-admin app broker identity required')


def release_check(evidence):
    identity = image.image_identity()
    for name in ('security', 'image-scan', 'image-runtime', 'staging'):
        report = gate.document(gate.SAFE / (name + '.json'))
        gate.require(report.get('status') == 'passed', 'release blocked: required source/image security/runtime gate is not passed')
        if name != 'security':
            gate.require(report.get('imageId') == identity, 'release blocked: stale image evidence')
        timestamp = dt.datetime.fromisoformat(report['timestamp'])
        gate.require(dt.timedelta(0) <= dt.datetime.now(dt.timezone.utc) - timestamp <= dt.timedelta(hours=72), 'release blocked: stale/future acceptance evidence')
        if name == 'staging':
            gate.require(set(report.get('cases', {})) == {'fresh', 'phase7'} and
                         all(value.get('status') == 'passed' for value in report['cases'].values()),
                         'release blocked: fresh and Phase Seven staging checks required')
    source = gate.document(gate.SAFE / 'security.json')
    commit, _ = gate.run(['git', 'rev-parse', 'HEAD'])
    changes, _ = gate.run(['git', 'status', '--porcelain', '--untracked-files=no'])
    gate.require(not changes and not source.get('trackedWorktreeDirty') and source.get('sourceCommit') == commit.decode().strip(),
                 'release blocked: source acceptance must match the committed release')
    evidence['imageId'] = identity


def assert_empty_project(project):
    gate.require(re.fullmatch(r'flowpay-stage-[a-f0-9]{32}', project), 'unsafe disposable project name')
    for resource in ('container', 'volume', 'network'):
        command = ['docker', resource, 'ls', '--quiet'] + (['--all'] if resource == 'container' else [])
        raw, _ = gate.run(command + ['--filter', 'label=com.docker.compose.project=' + project])
        gate.require(not raw.strip(), 'disposable project already has resources; refusing ownership/cleanup')


def original_migrations():
    baseline = '0b96400'
    for number in range(1, 12):
        files = list((ROOT / 'src/main/resources/db/migration').glob(f'V{number:03d}__*.sql'))
        gate.require(len(files) == 1, 'legacy migration inventory changed')
        raw, _ = gate.run(['git', 'show', baseline + ':' + files[0].relative_to(ROOT).as_posix()])
        gate.require(raw.replace(b'\r\n', b'\n') == files[0].read_bytes().replace(b'\r\n', b'\n'), 'Phase Seven migration changed; forward review required')


class Smoke:
    def __init__(self, mode, evidence, work, identity):
        self.mode, self.evidence, self.work, self.identity = mode, evidence, work, identity
        self.project = 'flowpay-stage-' + uuid.uuid4().hex
        self.secrets = work / 'secrets'
        self.fixtures = work / 'fixtures'
        self.secrets.mkdir()
        self.fixtures.mkdir()
        self.env_file = work / 'empty.env'
        self.env_file.touch()
        self.command = ['docker', 'compose', '--env-file', str(self.env_file), '--project-name', self.project,
                        '--file', str(STAGE / 'compose.yaml'), '--file', str(STAGE / 'tests/fixture.yaml')]

    def phase(self, name):
        self.evidence['stage'] = name
        print('staging ' + self.mode + ': ' + name, flush=True)

    def compose(self, *arguments, timeout=240):
        try:
            completed = subprocess.run(self.command + list(arguments), cwd=ROOT, capture_output=True, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise gate.GateError('owned staging command unavailable or timed out') from None
        output = completed.stdout
        if completed.returncode:
            safe = re.search(rb'FAIL: (owned [A-Za-z0-9 /:;,.()_-]{1,180})', output)
            categories = ('permission denied', 'unhealthy', 'invalid mount config', 'bind source path does not exist',
                          'address already in use', 'port is already allocated', 'non-overlapping IPv4 address pool',
                          'overlapping IPv4', 'pull access denied', 'no such file', 'invalid argument')
            # Return only fixed allow-listed labels, never raw third-party paths/logs.
            category = next((label for label in categories if label.encode().lower() in completed.stderr.lower()), 'raw diagnostics withheld')
            raise gate.GateError(safe[1].decode() if safe else 'owned staging command failed: ' + category)
        return output

    def probe(self, mode, **environment):
        arguments = ['--profile', 'checks', 'run', '--rm', '--no-deps']
        for key, value in environment.items():
            arguments += ['--env', key + '=' + str(value)]
        return self.compose(*arguments, 'probe', mode)

    def states(self, service):
        ids = self.compose('ps', '--all', '--quiet', service).decode().split()
        gate.require(bool(ids), 'missing owned staging service')
        raw, _ = gate.run(['docker', 'inspect'] + ids)
        values = json.loads(raw)
        gate.require(all(item['Config']['Labels'].get('com.docker.compose.project') == self.project for item in values), 'foreign staging container; aborting')
        return values

    def sql(self, statement):
        return self.compose('exec', '-T', 'postgres', 'psql', '-U', 'flowpay_stage', '-d', 'flowpay_stage', '-v', 'ON_ERROR_STOP=1', '-Atc', statement).strip()

    def wait_sql(self, statement):
        deadline = time.monotonic() + 150
        while self.sql(statement) != b't':
            gate.require(time.monotonic() < deadline, 'staging durable financial/consumer recovery failed')
            time.sleep(1)

    def recreate_app(self, replicas=1):
        self.compose('up', '--detach', '--no-deps', '--force-recreate', '--scale', 'app=' + str(replicas), 'app', timeout=300)
        deadline = time.monotonic() + 180
        while True:
            states = self.states('app')
            gate.require(not any(item['State'].get('OOMKilled') for item in states), 'staging app OOM is not acceptance')
            gate.require(not any(item['State']['Status'] == 'exited' for item in states), 'staging app exited before readiness')
            if len(states) == replicas and all(item['State'].get('Health', {}).get('Status') == 'healthy' for item in states):
                return
            gate.require(time.monotonic() < deadline, 'staging app health deadline exceeded')
            time.sleep(2)

    def snapshot(self):
        tables = ('payment_intents', 'payment_transactions', 'ledger_accounts', 'ledger_transactions', 'ledger_entries',
                  'webhook_endpoints', 'webhook_endpoint_events', 'webhook_events', 'webhook_deliveries', 'outbox_events')
        entries = []
        for table in tables:
            order = 'endpoint_id,event_type' if table == 'webhook_endpoint_events' else 'id'
            value = 'to_jsonb(t)' + ("-'traceparent'" if table in ('outbox_events', 'webhook_events') else '')
            entries.append("'" + table + "',(SELECT jsonb_agg(" + value + ' ORDER BY ' + order + ') FROM ' + table + ' t)')
        return self.sql('SELECT jsonb_build_object(' + ','.join(entries) + ')')

    def networks(self):
        self.phase('native TLS and private-network boundaries')
        targets = []
        ports = {'app': [8080, 9091], 'postgres': [5432], 'rabbitmq': [5672, 15672, 15692], 'egress': [3128],
                 'prometheus': [9090], 'grafana': [3000], 'collector': [4318, 8888], 'tempo': [3200, 4317]}
        for service, listeners in ports.items():
            for state in self.states(service):
                gate.require(not state['HostConfig'].get('PortBindings'), 'private staging listener published')
                for network in state['NetworkSettings']['Networks'].values():
                    for address in (network['IPAddress'], network.get('GlobalIPv6Address')):
                        if address:
                            targets += [[address, port] for port in listeners]
        os.environ['STAGING_PROTECTED_TARGETS'] = json.dumps(targets)
        self.compose('--profile', 'checks', 'run', '--rm', '--no-deps', 'public')
        edge = self.states('edge')[0]
        bindings = edge['HostConfig']['PortBindings']
        gate.require(set(bindings) == {'8443/tcp'} and all(item['HostIp'] == '127.0.0.1' for item in bindings['8443/tcp']), 'unexpected staging public binding')
        port = edge['NetworkSettings']['Ports']['8443/tcp'][0]['HostPort']
        context = ssl.create_default_context(cafile=str(self.secrets / 'edge-cert.pem'))
        with urllib.request.urlopen('https://127.0.0.1:' + port + '/readyz', context=context, timeout=10) as response:
            gate.require(response.status == 200, 'host TLS ingress failed')
        app = self.states('app')[0]
        proxy = self.states('egress')[0]['NetworkSettings']['Networks'][self.project + '_webhook']['IPAddress']
        private = self.states('postgres')[0]['NetworkSettings']['Networks'][self.project + '_data']['IPAddress']
        name = 'flowpay-ci-' + uuid.uuid4().hex
        gate.run(['docker', 'run', '--rm', '--name', name, '--label', 'flowpay.ci.owner=' + name,
                  '--network', 'container:' + app['Id'], '--read-only', '--user=10001:10001', '--cap-drop=ALL',
                  '--security-opt=no-new-privileges', '--memory=64m', '--cpus=0.5', '--pids-limit=128',
                  '--env', 'PROXY_IP=' + proxy, '--env', 'PRIVATE_TARGET=' + private,
                  '--mount', f'type=bind,source={STAGE / "tests/network.py"},target=/checks/network.py,readonly',
                  '--mount', f'type=bind,source={self.secrets / "cert.pem"},target=/run/merchant-ca.pem,readonly',
                  PYTHON, 'python', '-B', '/checks/network.py', 'egress'], timeout=60)
        self.evidence['nativeIPv4IPv6TlsIngressAndProxyBypass'] = 'passed; owned destinations, not an external firewall certification'

    def audit(self):
        for service in CORE - {'topology'}:
            for container in self.states(service):
                state = container['State']
                gate.require(state['Running'] and not state.get('OOMKilled'), 'staging service stopped or OOM: ' + service)
        legacy = 1 if self.mode == 'phase7' else 0
        self.wait_sql(f"""SELECT
            (SELECT COUNT(*)={3 + legacy} FROM payment_intents)
            AND (SELECT COUNT(*)=1 FROM payment_intents WHERE status='PARTIALLY_REFUNDED' AND refunded_amount_minor=1000 AND refund_reserved_minor=1000)
            AND (SELECT COUNT(*)=1 FROM payment_intents WHERE status='PROCESSING')
            AND (SELECT COUNT(*)=1 FROM payment_intents WHERE status='FAILED')
            AND (SELECT COUNT(*)={3 + legacy} FROM payment_transactions)
            AND (SELECT COUNT(*)=1 FROM payment_transactions WHERE status='UNKNOWN')
            AND (SELECT COUNT(*)=3 FROM refunds)
            AND (SELECT COUNT(*)=1 FROM refunds WHERE status='SUCCEEDED')
            AND (SELECT COUNT(*)=1 FROM refunds WHERE status='PROCESSING')
            AND (SELECT COUNT(*)=1 FROM refunds WHERE status='FAILED')
            AND (SELECT COUNT(*)={2 + legacy} FROM ledger_transactions)
            AND (SELECT COUNT(*)={4 + 2 * legacy} FROM ledger_entries)
            AND NOT EXISTS(SELECT 1 FROM ledger_entries GROUP BY ledger_transaction_id
                HAVING COUNT(*)<>2 OR SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END)<>0)
            AND (SELECT COUNT(*)={10 + legacy} FROM outbox_events)
            AND NOT EXISTS(SELECT 1 FROM outbox_events WHERE status<>'PUBLISHED')
            AND (SELECT COUNT(*)={10 + legacy} FROM webhook_events)
            AND (SELECT COUNT(*)={20 + legacy} FROM webhook_deliveries)
            AND (SELECT COUNT(*)={20 + legacy} FROM webhook_deliveries WHERE status='DELIVERED')
            AND NOT EXISTS(SELECT 1 FROM webhook_delivery_attempts WHERE finished_at IS NULL)
            AND (SELECT COUNT(*)=13 FROM flyway_schema_history WHERE success)
            """)

    def run(self):
        self.phase('external synthetic secrets and isolated infrastructure')
        base = re.search(r'^FROM (.+)$', (ROOT / 'Dockerfile').read_text(), re.M)[1]
        generator = 'flowpay-ci-' + uuid.uuid4().hex
        user = ['--user', f'{os.getuid()}:{os.getgid()}'] if os.name != 'nt' else []
        command = ['docker', 'run', '--rm', '--name', generator, '--label', 'flowpay.ci.owner=' + generator,
                   '--network=none', '--env', 'FLOWPAY_STAGE_DISPOSABLE=synthetic-only'] + user
        mounts = [(self.secrets, '/secrets', False), (self.secrets, '/cert', False), (STAGE / 'tests/init-secrets.sh', '/init.sh', True),
                  (ROOT / 'ops/monitoring/tests/init-secrets.sh', '/helpers/base-init.sh', True),
                  (ROOT / 'ops/egress/tests/init-tls.sh', '/helpers/merchant-tls.sh', True)]
        for source, destination, readonly in mounts:
            command += ['--mount', f'type=bind,source={source},target={destination}' + (',readonly' if readonly else '')]
        gate.run(command + ['--entrypoint=sh', base, '/init.sh'], timeout=90)
        raw = self.compose('--profile', 'checks', 'config', '--format', 'json')
        validate_config(json.loads(raw), self.project, fixture=True)
        self.compose('up', '--detach', 'postgres', 'rabbitmq', 'topology', 'egress', 'receiver', 'prometheus', 'grafana', 'collector', 'tempo', timeout=300)
        deadline = time.monotonic() + 150
        while self.states('postgres')[0]['State'].get('Health', {}).get('Status') != 'healthy' or self.states('topology')[0]['State']['Running']:
            gate.require(time.monotonic() < deadline, 'infrastructure/topology startup deadline exceeded')
            time.sleep(1)
        gate.require(self.states('topology')[0]['State']['ExitCode'] == 0, 'topology bootstrap must complete before app producers')
        for service in ('egress', 'receiver', 'grafana', 'prometheus', 'collector', 'tempo'):
            state = self.states(service)[0]['State']
            gate.require(state['Running'] and not state.get('OOMKilled'), 'staging infrastructure stopped or OOM: ' + service)
        self.evidence['topologyBeforeProducers'] = 'passed; bootstrap complete with no app or edge'
        key = gate.sha256(self.secrets / 'WEBHOOK_SECRET_ENCRYPTION_KEY')
        if self.mode == 'phase7':
            self.phase('Phase Seven financial/encrypted data upgrade')
            original_migrations()
            self.compose('--profile', 'checks', 'run', '--rm', '--no-deps', 'seed')
            before = self.snapshot()  # Sensitive ciphertext stays private; never written as evidence.
            history = self.sql('SELECT jsonb_agg(jsonb_build_array(version,checksum) ORDER BY installed_rank) FROM flyway_schema_history WHERE success')
            os.environ['STAGING_TEST_WORKERS'] = 'false'
            self.recreate_app()
            self.probe('ready', STAGING_QUIESCED='true')
            gate.require(before == self.snapshot(), 'Phase Seven financial/encrypted rows changed during migration')
            gate.require(history == self.sql('SELECT jsonb_agg(jsonb_build_array(version,checksum) ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version::int<=11'), 'legacy migration history changed')
            self.wait_sql('SELECT (SELECT traceparent IS NULL FROM outbox_events) AND (SELECT traceparent IS NULL FROM webhook_events)')
            self.evidence['phaseSevenToV013'] = 'passed; original checksums, finance and ciphertext preserved'
            os.environ['STAGING_TEST_WORKERS'] = 'true'
        self.phase('two replicas, TLS API and telemetry ingestion')
        self.recreate_app(replicas=2)
        self.compose('up', '--detach', '--no-deps', 'edge')
        self.probe('ready')
        self.compose('exec', '-T', 'edge', 'nginx', '-t', '-c', '/etc/flowpay/nginx.conf')
        self.compose('exec', '-T', 'prometheus', 'promtool', 'check', 'config', '/etc/prometheus/prometheus.yaml')
        self.compose('exec', '-T', 'collector', '/otelcol', 'validate', '--config=/etc/otel/config.yaml')
        self.phase('TLS API contracts and successful source fanout')
        self.probe('setup')
        self.phase('two-replica metrics, dashboards and stored trace')
        self.probe('monitoring', STAGING_REPLICAS='2')
        self.evidence['twoReplicaDiscoveryMetricsAndStoredTrace'] = 'passed; light functional traffic, not measured capacity'
        self.networks()
        self.phase('declined and unknown source outcomes')
        for outcome in ('DECLINED', 'UNKNOWN'):
            os.environ['STAGING_TEST_OUTCOME'] = outcome
            self.recreate_app()
            self.probe(outcome)
        deliveries = 21 if self.mode == 'phase7' else 20
        self.probe('replay', STAGING_DELIVERIES=deliveries)
        self.audit()
        self.phase('controlled duplicate consumer delivery')
        raw = self.sql("SELECT jsonb_agg(jsonb_build_object('eventId',event_id,'eventType',event_type,'aggregateType',aggregate_type,'aggregateId',aggregate_id,'occurredAt',payload->>'occurredAt','payload',payload) ORDER BY id) FROM outbox_events")
        gate.write_json(self.fixtures / 'duplicates.json', json.loads(raw))
        self.probe('duplicates')
        self.audit()
        self.phase('failed candidate, application rollback and restart')
        self.compose('stop', 'edge')
        before = self.snapshot()
        os.environ['STAGING_TEST_JWT_LOCATION'] = 'file:/run/secrets/not-present.pem'
        self.compose('up', '--detach', '--no-deps', '--force-recreate', 'app')
        deadline = time.monotonic() + 60
        while self.states('app')[0]['State']['Running']:
            gate.require(time.monotonic() < deadline, 'bad candidate did not fail fast')
            time.sleep(1)
        state = self.states('app')[0]['State']
        gate.require(state['ExitCode'] != 0 and not state['OOMKilled'], 'bad candidate must reject missing key without OOM')
        gate.require(before == self.snapshot(), 'bad candidate changed financial/delivery state')
        os.environ['STAGING_TEST_JWT_LOCATION'] = 'file:/run/secrets/jwt-private.pem'
        os.environ['STAGING_TEST_OUTCOME'] = 'SUCCESS'
        self.recreate_app()
        image.start_owned_container(self.project, self.states('edge')[0])
        self.probe('replay', STAGING_DELIVERIES=deliveries)
        self.compose('stop', '--timeout', '120', 'app')
        state = self.states('app')[0]
        gate.require(not state['State']['OOMKilled'] and state['State']['ExitCode'] in (0, 143), 'staging shutdown failed')
        image.start_owned_container(self.project, state)
        self.probe('replay', STAGING_DELIVERIES=deliveries)
        self.audit()
        gate.require(key == gate.sha256(self.secrets / 'WEBHOOK_SECRET_ENCRYPTION_KEY'), 'external encryption key changed')
        self.evidence.update(financialUnknownSuccessFailureAndConsumerDuplicates='passed', tlsWebhookHmacAndExternalKeyContinuity='passed',
                             failedConfigurationRollbackAndRestart='passed; same known-good app image, no database downgrade')


def smoke(evidence, modes):
    identity = image.image_identity()
    gate.require((ROOT / 'target/test-classes/com/flowpay/backend/webhook/infrastructure/security/StagingPhaseSevenFixture.class').is_file(), 'compiled staging upgrade fixture required')
    evidence.update(imageId=identity, jarSha256=gate.sha256(image.verified_jar()), releaseAcceptance='BLOCKED until independent source/image security gates pass')
    host, _ = gate.run(['docker', 'info', '--format', '{{json .}}'])
    info = json.loads(host)
    gate.require(info['OSType'] == 'linux' and info['Architecture'] == 'x86_64', 'validated staging platform is Linux/amd64 only')
    evidence['host'] = {key: info[key] for key in ('OSType', 'Architecture', 'NCPU', 'MemTotal', 'KernelVersion', 'ServerVersion')}
    version, _ = gate.run(['docker', 'compose', 'version', '--short'])
    evidence['composeVersion'] = gate.token(version.decode().strip())
    evidence['cases'] = {}
    variables = ('FLOWPAY_IMAGE', 'FLOWPAY_TEST_CLASSES', 'STAGING_SECRETS_DIR', 'STAGING_FIXTURE_DIR', 'STAGING_HTTPS_PORT',
                 'STAGING_TEST_OUTCOME', 'STAGING_TEST_WORKERS', 'STAGING_TEST_JWT_LOCATION', 'STAGING_PROTECTED_TARGETS')
    previous = {key: os.environ.get(key) for key in variables}
    try:
        for mode in modes:
            with tempfile.TemporaryDirectory(prefix='flowpay-staging-') as directory:
                case = evidence['cases'][mode] = {'status': 'incomplete'}
                context = Smoke(mode, case, Path(directory), identity)
                os.environ.update(FLOWPAY_IMAGE=identity, FLOWPAY_TEST_CLASSES=str(ROOT / 'target/test-classes'),
                                  STAGING_SECRETS_DIR=str(context.secrets), STAGING_FIXTURE_DIR=str(context.fixtures),
                                  STAGING_HTTPS_PORT='0', STAGING_TEST_OUTCOME='SUCCESS', STAGING_TEST_WORKERS='true',
                                  STAGING_TEST_JWT_LOCATION='file:/run/secrets/jwt-private.pem', STAGING_PROTECTED_TARGETS='[]')
                assert_empty_project(context.project)
                try:
                    context.run()
                    case['status'] = 'passed'
                finally:
                    context.compose('--profile', 'checks', 'down', '--volumes', '--remove-orphans', timeout=240)
                    assert_empty_project(context.project)
                    case['ownedSyntheticCleanup'] = 'passed'
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gate', choices=('release-check', 'smoke'))
    parser.add_argument('--database', choices=('all', 'fresh', 'phase7'), default='all')
    args = parser.parse_args()
    evidence = {'status': 'incomplete', 'gate': 'staging-' + args.gate, 'timestamp': dt.datetime.now(dt.timezone.utc).isoformat()}
    path = gate.SAFE / ('staging.json' if args.gate == 'smoke' else 'staging-release.json')
    path.unlink(missing_ok=True)
    try:
        if args.gate == 'smoke':
            # Exercise the higher-risk legacy/key migration before repeating fresh boot.
            smoke(evidence, ('phase7', 'fresh') if args.database == 'all' else (args.database,))
        else:
            release_check(evidence)
        evidence['status'] = 'passed'
    except gate.GateError as error:
        evidence.update(status='failed', reason=str(error))
    except Exception:
        evidence.update(status='failed', reason='unexpected staging gate failure; raw sensitive diagnostics withheld')
    gate.write_json(path, evidence)
    print('staging-' + args.gate + ': ' + evidence['status'] + ' (safe evidence: ' + path.relative_to(ROOT).as_posix() + ')')
    return 0 if evidence['status'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
