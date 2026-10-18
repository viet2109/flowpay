"""P8-T16 isolated, authenticated backup/restore acceptance. No deployment bypass.

Only fresh invocation-owned synthetic projects; no generic production restore
CLI. All plaintext, synthetic credentials and raw diagnostics remain external
and temporary. No financial/event/attempt SQL writes or PUBLISHED reset.
"""
import base64
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
import uuid

SPEC = importlib.util.spec_from_file_location('drills', Path(__file__).resolve().parents[1] / 'resilience/resilience.py')
drills = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(drills)
staging, gate, ROOT = drills.staging, drills.gate, drills.ROOT
HERE = ROOT / 'ops/backup'
SPEC = importlib.util.spec_from_file_location('archives', HERE / 'archive.py')
archives = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(archives)
TABLES = ('users', 'refresh_tokens', 'merchants', 'merchant_members', 'merchant_api_keys',
          'payment_intents', 'payment_transactions', 'refunds', 'idempotency_records',
          'ledger_accounts', 'ledger_transactions', 'ledger_entries', 'outbox_events',
          'webhook_endpoints', 'webhook_endpoint_events', 'webhook_events',
          'webhook_deliveries', 'webhook_delivery_attempts', 'flyway_schema_history')
ORDER_BY = {'merchant_members': 'merchant_id,user_id', 'webhook_endpoint_events': 'endpoint_id,event_type',
            'flyway_schema_history': 'installed_rank'}
SECRET_FILES = {'DB_PASSWORD', 'RABBITMQ_PASSWORD', 'RABBITMQ_ADMIN_PASSWORD', 'GRAFANA_ADMIN_PASSWORD',
                'WEBHOOK_SECRET_ENCRYPTION_KEY', 'LEGACY_WEBHOOK_SECRET', 'jwt-private.pem', 'jwt-public.pem',
                'edge-cert.pem', 'edge-key.pem', 'cert.pem', 'key.pem', 'trust.p12',
                'untrusted-cert.pem', 'untrusted-key.pem', 'dns-only-cert.pem', 'dns-only-key.pem'}
ENV_KEYS = ('FLOWPAY_IMAGE', 'FLOWPAY_TEST_CLASSES', 'STAGING_SECRETS_DIR', 'STAGING_FIXTURE_DIR', 'STAGING_HTTPS_PORT',
            'STAGING_TEST_OUTCOME', 'STAGING_TEST_WORKERS', 'STAGING_TEST_JWT_LOCATION', 'STAGING_PROTECTED_TARGETS',
            'BACKUP_CONSUMERS_ENABLED', 'BACKUP_HTTP_ENABLED')


def validate_policy(value):
    gate.require(value.get('version') == 'P8-T16-v1' and value.get('rpoSeconds') == 86400 and
                 value.get('rtoSeconds') == 900 and value.get('cadenceHours') == 24 and
                 value.get('retention') == {'daily': 7, 'weekly': 4} and
                 value.get('replay') == 'original-event-facts-isolated-lab-only' and
                 value.get('rabbitNode') == 'rabbit@flowpay-backup-node', 'unreviewed backup policy; no moving acceptance targets')


def external(path):
    path = Path(path)
    gate.require(path.is_absolute() and not path.is_symlink() and not path.resolve().is_relative_to(ROOT.resolve()) and
                 all(not parent.is_symlink() for parent in path.parents), 'backup plaintext/key path must be external and not a link')
    return path


def fingerprint():
    value = hashlib.sha256()
    for path in sorted(HERE.iterdir()):
        if path.suffix in ('.py', '.yaml', '.json'):
            value.update(path.name.encode() + b'\0' + path.read_bytes().replace(b'\r\n', b'\n') + b'\0')
    return value.hexdigest()


def helper(image, mounts, arguments, *, entrypoint=None, caps=(), allowed=(0,)):
    name = 'flowpay-ci-' + uuid.uuid4().hex
    command = ['docker', 'run', '--rm', '--name', name, '--label', 'flowpay.ci.owner=' + name,
               '--network=none', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
               '--memory=128m', '--cpus=1', '--pids-limit=128', '--tmpfs=/tmp:rw,nosuid,nodev,size=16m']
    for cap in caps:
        command += ['--cap-add=' + cap]
    if os.name != 'nt' and not caps:
        command += ['--user', f'{os.getuid()}:{os.getgid()}']
    for mount in mounts:
        command += ['--mount', mount]
    if entrypoint:
        command += ['--entrypoint=' + entrypoint]
    return gate.run(command + [image] + arguments, timeout=180, allowed=allowed)


class Crypto:
    def __init__(self, work):
        self.work = external(work)
        self.image = re.search(r'^FROM (.+)$', (ROOT / 'Dockerfile').read_text(), re.M)[1]

    def openssl(self, arguments, allowed=(0,)):
        return helper(self.image, [f'type=bind,source={self.work},target=/vault'], arguments,
                      entrypoint='openssl', allowed=allowed)

    def recipient(self, role):
        gate.require(role in ('data', 'keys', 'wrong'), 'unknown backup recipient role')
        directory = self.work / role
        directory.mkdir(mode=0o700)
        self.openssl(['req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '1', '-subj', '/CN=owned-backup-' + role,
                      '-keyout', '/vault/' + role + '/private.pem', '-out', '/vault/' + role + '/public.pem'])

    def encrypt(self, source, destination, role):
        gate.require(role in ('data', 'keys') and source.parent == self.work and destination.parent == self.work and
                     source.is_file() and not source.is_symlink() and not destination.exists() and not destination.is_symlink(),
                     'encrypted bundle path/overwrite refused')
        self.openssl(['cms', '-encrypt', '-binary', '-aes-256-gcm', '-in', '/vault/' + source.name,
                      '-outform', 'DER', '-out', '/vault/' + destination.name, '/vault/' + role + '/public.pem'])
        return gate.sha256(destination)  # Trusted catalog lives separately from encrypted bundle.

    def decrypt(self, source, destination, role, expected):
        key = self.work / role / 'private.pem'
        gate.require(role in ('data', 'keys', 'wrong') and source.parent == self.work and destination.parent == self.work and
                     source.is_file() and not source.is_symlink() and gate.sha256(source) == expected and
                     not destination.exists() and not destination.is_symlink() and key.is_file() and not key.is_symlink(),
                     'bundle identity/key/destination rejected before restore')
        # OpenSSL may emit plaintext before reporting a bad GCM tag. Quarantine it;
        # never pipe directly to tar, PostgreSQL or another consumer.
        quarantine = destination.with_suffix('.quarantine')
        gate.require(not quarantine.exists() and not quarantine.is_symlink(), 'quarantine overwrite refused')
        try:
            self.openssl(['cms', '-decrypt', '-binary', '-inform', 'DER', '-in', '/vault/' + source.name,
                          '-inkey', '/vault/' + role + '/private.pem', '-out', '/vault/' + quarantine.name])
            quarantine.replace(destination)
        finally:
            quarantine.unlink(missing_ok=True)


class Recovery(drills.Drill):
    def __init__(self, evidence, work, identity):
        super().__init__(evidence, work, identity, {'recoverySeconds': 180})
        self.mode = 'backup-restore'
        self.command = self.command[:-2] + ['--file', str(HERE / 'fixture.yaml')]

    def sql(self, statement, database='flowpay_stage'):
        gate.require(database in ('flowpay_stage', 'flowpay_restore_reference'), 'unreviewed diagnostic database')
        return self.compose('exec', '-T', '--env', 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=10000',
                            'postgres', 'psql', '-U', 'flowpay_stage', '-d', database, '-v', 'ON_ERROR_STOP=1', '-Atc', statement).strip()

    def activate(self, *, consumers=False, http=False):
        os.environ.update(FLOWPAY_IMAGE=self.identity, FLOWPAY_TEST_CLASSES=str(ROOT / 'target/test-classes'),
                          STAGING_SECRETS_DIR=str(self.secrets), STAGING_FIXTURE_DIR=str(self.fixtures),
                          STAGING_HTTPS_PORT='0', STAGING_TEST_OUTCOME='SUCCESS', STAGING_TEST_WORKERS='true',
                          STAGING_TEST_JWT_LOCATION='file:/run/secrets/jwt-private.pem', STAGING_PROTECTED_TARGETS='[]',
                          BACKUP_CONSUMERS_ENABLED=str(consumers).lower(), BACKUP_HTTP_ENABLED=str(http).lower())

    def generate(self):
        base = re.search(r'^FROM (.+)$', (ROOT / 'Dockerfile').read_text(), re.M)[1]
        mounts = [f'type=bind,source={self.secrets},target=/secrets', f'type=bind,source={self.secrets},target=/cert']
        for source, target in ((staging.STAGE / 'tests/init-secrets.sh', '/init.sh'),
                               (ROOT / 'ops/monitoring/tests/init-secrets.sh', '/helpers/base-init.sh'),
                               (ROOT / 'ops/egress/tests/init-tls.sh', '/helpers/merchant-tls.sh')):
            mounts += [f'type=bind,source={source},target={target},readonly']
        # The generator explicitly refuses retained/nonempty secret directories.
        helper(base, mounts, ['-ec', 'export FLOWPAY_STAGE_DISPOSABLE=synthetic-only; sh /init.sh'], entrypoint='sh')
        gate.require({p.name for p in self.secrets.iterdir()} == SECRET_FILES, 'unreviewed secret file inventory')

    def validate(self):
        config = json.loads(self.compose('--profile', 'checks', 'config', '--format', 'json'))
        staging.validate_config(config, self.project, fixture=True)
        rabbit = config['services']['rabbitmq']
        gate.require(rabbit['hostname'] == 'flowpay-backup-node' and
                     rabbit['environment']['RABBITMQ_NODENAME'] == 'rabbit@flowpay-backup-node', 'incompatible broker node identity')
        self.rabbit_image = rabbit['image']

    def infrastructure(self):
        self.compose('up', '--detach', 'postgres', 'rabbitmq', 'topology', 'egress', 'receiver', 'prometheus', 'grafana', 'collector', 'tempo', timeout=300)
        self.wait(lambda: not self.states('topology')[0]['State']['Running'] and
                  self.states('postgres')[0]['State'].get('Health', {}).get('Status') == 'healthy', 'restore infrastructure')
        gate.require(self.states('topology')[0]['State']['ExitCode'] == 0, 'owned broker topology failed')

    def application(self, consumers, http):
        self.activate(consumers=consumers, http=http)
        self.recreate_app()
        self.compose('up', '--detach', '--no-deps', '--force-recreate', 'edge')
        self.probe_json('ready')

    def snapshot(self):
        entries = []
        for table in TABLES:
            order = ORDER_BY.get(table, 'id')
            entries.append("'" + table + "',(SELECT jsonb_agg(to_jsonb(t) ORDER BY " + order + ') FROM ' + table + ' t)')
        tables = json.loads(self.sql('SELECT jsonb_build_object(' + ','.join(entries) + ')'))
        inventory = self.sql("SELECT jsonb_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public'")
        gate.require(set(json.loads(inventory)) == set(TABLES), 'backup must cover every public table')
        sequences = json.loads(self.sql("SELECT jsonb_agg(to_jsonb(s) ORDER BY sequencename) FROM pg_sequences s WHERE schemaname='public'"))
        return {'tables': tables, 'sequences': sequences, 'schema': self.schema()}

    def schema(self, database='flowpay_stage'):
        return json.loads(self.sql("""SELECT jsonb_build_object(
          'constraints',(SELECT jsonb_agg(jsonb_build_object('table',r.relname,'name',c.conname,
            'type',c.contype,'validated',c.convalidated,'deferrable',c.condeferrable,'deferred',c.condeferred,
            'definition',pg_get_constraintdef(c.oid)) ORDER BY r.relname,c.conname)
            FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid
            JOIN pg_namespace n ON n.oid=r.relnamespace WHERE n.nspname='public'),
          'indexes',(SELECT jsonb_agg(jsonb_build_object('table',tablename,'name',indexname,'definition',indexdef)
            ORDER BY tablename,indexname) FROM pg_indexes WHERE schemaname='public'),
          'columns',(SELECT jsonb_agg(jsonb_build_object('table',table_name,'column',column_name,
            'type',data_type,'nullable',is_nullable,'default',column_default,'identity',identity_generation)
            ORDER BY table_name,ordinal_position) FROM information_schema.columns WHERE table_schema='public'))""", database))

    def stop_app(self):
        self.compose('stop', '--timeout', '120', 'edge', 'app')
        gate.require(all(not s['State']['Running'] and not s['State']['OOMKilled'] and s['State']['ExitCode'] in (0, 143)
                         for s in self.states('app')), 'source was not gracefully quiesced')

    def park(self):
        self.compose('--profile', 'checks', 'down', '--remove-orphans', timeout=240)  # Keep owned source volumes until final cleanup.

    def volume(self):
        state = self.states('rabbitmq')[0]
        gate.require(not state['State']['Running'] and not state['State']['OOMKilled'] and state['State']['ExitCode'] == 0 and
                     state['Config']['Image'] == self.rabbit_image, 'cold broker must be stopped and version-compatible')
        mounts = [v for v in state['Mounts'] if v['Destination'] == '/var/lib/rabbitmq' and v['Type'] == 'volume']
        gate.require(len(mounts) == 1, 'exact broker volume required')
        name = mounts[0]['Name']
        gate.require(name == self.project + '_rabbit-data', 'foreign or unexpected broker volume')
        value = json.loads(gate.run(['docker', 'volume', 'inspect', name])[0])[0]
        gate.require(value.get('Labels', {}).get('com.docker.compose.project') == self.project and
                     value['Labels'].get('com.docker.compose.volume') == 'rabbit-data', 'foreign broker volume labels')
        gate.require(not gate.run(['docker', 'ps', '--quiet', '--filter', 'volume=' + name])[0].strip(), 'broker volume in use')
        return name

    def broker_archive(self, transport, operation):
        volume = self.volume()  # Revalidate exact absolute/owned target before every volume operation.
        external(transport)
        helper(staging.PYTHON, [f'type=volume,source={volume},target=/state' + (',readonly' if operation == 'pack' else ''),
                               f'type=bind,source={transport},target=/transport' + (',readonly' if operation == 'unpack' else ''),
                               f'type=bind,source={HERE / "archive.py"},target=/archive.py,readonly'],
               ['python', '-B', '/archive.py', operation], caps=('CHOWN', 'DAC_OVERRIDE', 'FOWNER'))

    def queue_counts(self):
        values = self.probe_json('queues')
        gate.require(set(values) == {'flowpay.ledger.events', 'flowpay.ledger.events.dlq', 'flowpay.webhook.events', 'flowpay.webhook.events.dlq'} and
                     all(not v['unacked'] for v in values.values()), 'unexpected queue inventory or in-flight messages')
        return values

    def recovery_audit(self, payments):
        value = json.loads(self.sql((drills.DRILLS / 'audit.sql').read_text()))
        state = self.control()
        gate.require(value['invariants'] and value['payments'] == payments and not state['invalid'] and
                     len(state['receipts']) == value['deliveries'], 'restore financial/HMAC/history audit failed')
        gate.require(all(v['ready'] == 0 and v['unacked'] == 0 for v in self.queue_counts().values()), 'restore broker/DLQ not drained')
        gate.require(self.sql("""SELECT NOT EXISTS(SELECT 1 FROM webhook_events w
          JOIN outbox_events o ON w.source_event_id=o.event_id WHERE o.traceparent IS NOT NULL
          AND (w.traceparent IS NULL OR substring(w.traceparent,4,32)<>substring(o.traceparent,4,32)))""") == b't',
          'retained source trace identity lost during recovery')
        return value

    def envelopes(self):
        # Only original immutable stored event facts/IDs. No generated replacement
        # IDs, provider calls, DLQ reads or source-status changes.
        raw = self.sql("SELECT jsonb_agg(jsonb_build_object('traceparent',traceparent,'envelope',jsonb_build_object('eventId',event_id,'eventType',event_type,'aggregateType',aggregate_type,'aggregateId',aggregate_id,'occurredAt',payload->>'occurredAt','payload',payload)) ORDER BY id) FROM outbox_events")
        gate.write_json(self.fixtures / 'duplicates.json', json.loads(raw))

    def duplicate_replay_for_recovery(self):
        before = self.snapshot()['tables']
        self.envelopes()
        result = self.probe_json('original-replay')
        self.drain()
        after = self.snapshot()['tables']
        for table in ('payment_intents', 'payment_transactions', 'refunds', 'idempotency_records', 'outbox_events'):
            gate.require(before[table] == after[table], 'controlled replay changed source financial/event facts')
        for table in ('ledger_transactions', 'ledger_entries', 'webhook_events', 'webhook_delivery_attempts'):
            rows = before[table] or []
            if table == 'webhook_delivery_attempts':
                rows = [row for row in rows if row['finished_at'] is not None]
            gate.require(all(row in after[table] for row in rows), 'controlled replay lost or changed completed history')
        return result

    def duplicate_control(self):
        before = self.snapshot()['tables']
        requests = self.control()['requests']
        self.envelopes()
        result = self.probe_json('original-replay')
        self.drain()
        gate.require(before == self.snapshot()['tables'] and self.control()['requests'] == requests,
                     'duplicate reprocessing changed rows or sent extra HTTP')
        return result

    def write_synthetic_key(self, value):
        path = self.secrets / 'WEBHOOK_SECRET_ENCRYPTION_KEY'
        external(path)
        gate.require(path.is_file() and not path.is_symlink(), 'synthetic key destination must already be an owned regular file')
        path.chmod(0o600)
        try:
            path.write_bytes(value)
        finally:
            # File-backed Compose secrets must remain readable by app UID10001,
            # even when the real Linux host runner has another UID. Parent stays
            # private; this is only an invocation-owned synthetic key copy.
            path.chmod(0o444)

    def key_controls(self):
        self.phase('post-restore new workflow awaiting original AES key')
        self.application(True, False)
        self.probe_json('workflow', 'key-continuity')
        self.wait(lambda: self.sql('SELECT COUNT(*)=16 FROM webhook_events') == b't' and
                  self.sql('SELECT COUNT(*)=8 FROM ledger_transactions') == b't', 'new facts await recovered encryption key')
        self.stop_app()
        before = self.snapshot()['tables']
        path = self.secrets / 'WEBHOOK_SECRET_ENCRYPTION_KEY'
        key = path.read_bytes()
        try:
            self.phase('missing AES key startup rejection')
            self.write_synthetic_key(b'')
            self.activate()
            self.compose('up', '--detach', '--no-deps', '--force-recreate', 'app')
            self.wait(lambda: not self.states('app')[0]['State']['Running'], 'missing encryption key fails startup', 90)
            state = self.states('app')[0]['State']
            gate.require(state['ExitCode'] != 0 and not state['OOMKilled'] and self.snapshot()['tables'] == before,
                         'missing encryption key did not fail safely')
            self.phase('wrong AES key rejects decryption without HTTP')
            self.write_synthetic_key(base64.b64encode(os.urandom(32)))
            requests = self.control()['requests']
            self.application(True, True)
            self.wait(lambda: self.sql("SELECT EXISTS(SELECT 1 FROM webhook_delivery_attempts WHERE error_message='TRANSPORT_FAILURE' AND finished_at IS NOT NULL)") == b't',
                      'wrong AES key must fail authenticated decryption before HTTP')
            self.stop_app()
            gate.require(self.control()['requests'] == requests and not self.control()['invalid'], 'wrong encryption key sent HTTP')
            failed = json.loads(self.sql("SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM webhook_delivery_attempts a WHERE error_message='TRANSPORT_FAILURE'"))
            gate.require(bool(failed), 'wrong-key attempt history absent')
            for table in ('payment_intents', 'payment_transactions', 'refunds', 'idempotency_records', 'outbox_events',
                          'webhook_endpoints', 'ledger_transactions', 'ledger_entries'):
                gate.require(self.snapshot()['tables'][table] == before[table], 'wrong key changed source or encrypted endpoint')
        finally:
            self.write_synthetic_key(key)
        self.phase('original AES key recovery and retained normal retry')
        self.application(True, True)
        self.drain()
        self.probe_json('replay', 'key-continuity')
        self.recovery_audit(4)
        attempts = self.snapshot()['tables']['webhook_delivery_attempts']
        gate.require(all(row in attempts for row in failed), 'correct-key retry lost wrong-key attempt history')
        return {'missingEncryptionKeyFailsStartup': True, 'wrongEncryptionKeySentNoHttp': True,
                'originalKeyRecoveredWithRetainedAttempts': True, 'newPostRestoreWorkflow': True}


def authenticate(crypto, catalog, work):
    data, keys = work / 'restored-data.tar', work / 'restored-keys.tar'
    # Never consume either bundle until both independently authenticate. Remove
    # the first plaintext immediately if the second bundle/key is unavailable.
    try:
        crypto.decrypt(work / 'data.cms', data, 'data', catalog['data'])
        crypto.decrypt(work / 'keys.cms', keys, 'keys', catalog['keys'])
    except Exception:
        data.unlink(missing_ok=True)
        keys.unlink(missing_ok=True)
        raise
    return data, keys


def reconcile(expected, actual, policy, *, approve=False):
    gate.require(set(actual) == set(expected), 'broker checkpoint inventory mismatch')
    mismatch = any(actual[k]['ready'] != expected[k]['ready'] or actual[k]['unacked'] for k in expected)
    if mismatch:
        gate.require(approve and policy['replay'] == 'original-event-facts-isolated-lab-only',
                     'broker checkpoint mismatch: consumers remain disabled; explicit original-event replay approval required')
    return mismatch


def unchanged_after_api_replay(before, after):
    # Authenticating a real API key intentionally records last_used_at. A
    # conflicting idempotency INSERT may also consume a PostgreSQL sequence.
    # Do not mistake these real audit effects for restore data corruption, and
    # do not hide changes to any financial/authentication credential field.
    left, right = before['tables'], after['tables']
    if set(left) != set(right) or before['schema'] != after['schema']:
        return False
    for table in left:
        if table != 'merchant_api_keys':
            if left[table] != right[table]:
                return False
            continue
        if len(left[table]) != len(right[table]):
            return False
        for old, new in zip(left[table], right[table]):
            if ({k: v for k, v in old.items() if k != 'last_used_at'} !=
                    {k: v for k, v in new.items() if k != 'last_used_at'} or
                    (old['last_used_at'] is not None and (new['last_used_at'] is None or
                     dt.datetime.fromisoformat(new['last_used_at']) < dt.datetime.fromisoformat(old['last_used_at'])))):
                return False
    if len(before['sequences']) != len(after['sequences']):
        return False
    for old, new in zip(before['sequences'], after['sequences']):
        if ({k: v for k, v in old.items() if k != 'last_value'} != {k: v for k, v in new.items() if k != 'last_value'} or
                (old['last_value'] is not None and (new['last_value'] is None or new['last_value'] < old['last_value']))):
            return False
    return True


def canonical_definitions(value):
    # Export array order is not a broker fact. Preserve every field, including
    # password hashes/permissions/queue arguments, without logging their values.
    return {key: sorted(json.dumps(item, sort_keys=True) for item in items) if isinstance(items, list) else items
            for key, items in value.items()}


def validate_reference_schema(original, reference):
    # PostgreSQL deparse/reparse can push a VARCHAR[] -> TEXT[] CHECK cast
    # into each array element. Do not regex-rewrite or ignore target checks.
    # Compare target expressions EXACTLY with a schema-only restore of the same
    # trusted source dump on the same pinned PostgreSQL, prepared before loss.
    gate.require(original['columns'] == reference['columns'] and original['indexes'] == reference['indexes'] and
                 len(original['constraints']) == len(reference['constraints']), 'logical schema export changed columns/index/constraint inventory')
    for old, new in zip(original['constraints'], reference['constraints']):
        gate.require({k: v for k, v in old.items() if k != 'definition'} ==
                     {k: v for k, v in new.items() if k != 'definition'} and
                     (old['type'] == 'c' or old['definition'] == new['definition']),
                     'logical schema export changed constraint identity/enforcement')


def run(evidence):
    policy = gate.document(HERE / 'policy.json')
    validate_policy(policy)
    digest = fingerprint()
    identity = staging.image.image_identity()
    gate.require(identity == policy['applicationImage'] and gate.sha256(staging.image.verified_jar()) == policy['jarSha256'], 'unchanged T13 verified artifact required')
    gate.require(gate.document(gate.SAFE / 'resilience.json').get('status') == 'passed' and
                 gate.sha256(gate.SAFE / 'resilience.json') == policy['priorDrillSha256'], 'matching real T15 drill required')
    host = json.loads(gate.run(['docker', 'info', '--format', '{{json .}}'])[0])
    compose = gate.run(['docker', 'compose', 'version', '--short'])[0].decode().strip()
    drills.validate_host(host, compose, gate.document(drills.DRILLS / 'manifest.json'))
    gate.require(not gate.run(['docker', 'ps', '--quiet'])[0].strip(), 'active unrelated containers; never stop/adopt workloads')
    evidence.update(imageId=identity, jarSha256=policy['jarSha256'], workloadSha256=digest,
                    policySha256=gate.sha256(HERE / 'policy.json'), rpoTargetSeconds=policy['rpoSeconds'], rtoTargetSeconds=policy['rtoSeconds'],
                    host={k: host[k] for k in ('OSType', 'Architecture', 'NCPU', 'MemTotal', 'KernelVersion', 'ServerVersion')},
                    composeVersion=compose, cases=[], securityRelease='BLOCKED; no waiver', offHostScheduleProvisioned=False)
    previous = {k: os.environ.get(k) for k in ENV_KEYS}
    contexts = []
    try:
        with tempfile.TemporaryDirectory(prefix='flowpay-backup-') as root:
            root = external(root)
            vault = root / 'vault'
            vault.mkdir(mode=0o700)
            crypto = Crypto(vault)
            for role in ('data', 'keys', 'wrong'):
                crypto.recipient(role)
            source_work = root / 'source'
            source_work.mkdir()
            source = Recovery(evidence, source_work, identity)
            contexts.append(source)
            source.activate(consumers=True, http=True)
            staging.assert_empty_project(source.project)
            try:
                source.phase('consistent source checkpoint with retained history, pending HTTP and broker work')
                source.generate()
                source.validate()
                source.phase('initialize source infrastructure')
                source.infrastructure()
                source.phase('initialize source application')
                source.application(True, True)
                source.phase('initialize synthetic API client and JWT')
                source.probe_json('setup')
                source.probe_json('jwt-save')
                source.phase('seed retained completed history')
                source.probe_json('workflow', 'history')
                source.drain()
                source.phase('seed pending HTTP checkpoint')
                source.application(True, False)
                source.probe_json('workflow', 'pending-http')
                source.wait(lambda: source.sql('SELECT COUNT(*)=8 FROM webhook_events') == b't' and
                            source.sql('SELECT COUNT(*)=4 FROM ledger_transactions') == b't', 'consumed facts before HTTP')
                source.phase('seed published but unconsumed broker checkpoint')
                source.application(False, False)
                source.probe_json('workflow', 'queued')
                source.wait(lambda: source.sql("SELECT COUNT(*)=12 AND bool_and(status='PUBLISHED') FROM outbox_events") == b't', 'published source checkpoint')
                source.stop_app()
                source.wait(lambda: sum(v['ready'] for v in source.queue_counts().values()) == 6, 'broker checkpoint visibility')
                source.phase('capture all-table and sequence checkpoint')
                checkpoint = source.snapshot()
                queues = source.queue_counts()
                definitions = source.probe_json('definitions')
                receiver = source.control()
                source.phase('capture consistent database and cold broker backups')
                data_dir, key_dir = vault / 'data-plain', vault / 'keys-plain'
                data_dir.mkdir(mode=0o700)
                key_dir.mkdir(mode=0o700)
                snapshot_time = time.time()
                (data_dir / 'database.dump').write_bytes(source.compose('exec', '-T', 'postgres', 'pg_dump', '-U', 'flowpay_stage', '-d', 'flowpay_stage', '--format=custom'))
                # Reference is a distinct empty DB in the owned source container,
                # never a rewrite of original financial tables or constraints.
                source.compose('exec', '-T', 'postgres', 'createdb', '-U', 'flowpay_stage', 'flowpay_restore_reference')
                source.compose('cp', str(data_dir / 'database.dump'), 'postgres:/tmp/reference.dump')
                source.compose('exec', '-T', 'postgres', 'pg_restore', '-U', 'flowpay_stage', '-d', 'flowpay_restore_reference',
                               '--schema-only', '--no-owner', '--no-acl', '--exit-on-error', '/tmp/reference.dump')
                original_schema = checkpoint['schema']
                reference_schema = source.schema('flowpay_restore_reference')
                validate_reference_schema(original_schema, reference_schema)
                checkpoint['schema'] = reference_schema
                gate.write_json(data_dir / 'checkpoint.json', {'snapshot': checkpoint, 'queues': queues, 'node': policy['rabbitNode'],
                                                              'rabbitImage': source.rabbit_image, 'snapshotTime': snapshot_time,
                                                              'originalSourceSchema': original_schema})
                gate.write_json(data_dir / 'definitions.json', definitions)
                for path in source.secrets.iterdir():
                    shutil.copyfile(path, key_dir / path.name)
                gate.write_json(key_dir / 'receiver.json', receiver)
                source.compose('stop', '--timeout', '120', 'rabbitmq')
                source.broker_archive(data_dir, 'pack')
                source.park()
                for role, directory in (('data', data_dir), ('keys', key_dir)):
                    archives.pack(directory, vault / (role + '.tar'))
                catalog = {role: crypto.encrypt(vault / (role + '.tar'), vault / (role + '.cms'), role) for role in ('data', 'keys')}
                # Remove all operational plaintext before restore; only independent
                # checkpoint expectations remain in private process memory.
                shutil.rmtree(data_dir)
                shutil.rmtree(key_dir)
                for role in ('data', 'keys'):
                    (vault / (role + '.tar')).unlink()
                for name in ('data.cms', 'keys.cms'):
                    evidence[name.split('.')[0] + 'EncryptedBytes'] = (vault / name).stat().st_size
                source.phase('wrong recipient and authenticated tamper rejection before any restore')
                for mode in ('wrong', 'tamper'):
                    bad = vault / ('rejected-' + mode + '.tar')
                    original = vault / 'data.cms'
                    expected = catalog['data']
                    if mode == 'tamper':
                        original = vault / 'tampered.cms'
                        raw = bytearray((vault / 'data.cms').read_bytes())
                        raw[-8] ^= 1
                        original.write_bytes(raw)
                        expected = gate.sha256(original)  # Prove AEAD, not merely catalog mismatch.
                    try:
                        crypto.decrypt(original, bad, 'wrong' if mode == 'wrong' else 'data', expected)
                    except gate.GateError:
                        gate.require(not bad.exists() and not bad.with_suffix('.quarantine').exists(), 'rejected plaintext survived')
                    else:
                        raise gate.GateError('bad backup authentication accepted')
                evidence['wrongRecipientAndTamperRejected'] = True
                for mode in ('matched', 'mismatch'):
                    source.phase('fresh isolated restore ' + mode)
                    start = time.monotonic()
                    data_tar, key_tar = authenticate(crypto, catalog, vault)
                    work = root / mode
                    work.mkdir()
                    target = Recovery(evidence, work, identity)
                    contexts.append(target)
                    staging.assert_empty_project(target.project)
                    gate.require(target.project != source.project, 'source and destination must differ')
                    restored = work / 'restored'
                    restored.mkdir()
                    archives.unpack(data_tar, restored)
                    archives.unpack(key_tar, target.secrets)
                    data_tar.unlink()
                    key_tar.unlink()
                    client_state = json.loads((target.secrets / 'receiver.json').read_text())
                    (target.secrets / 'receiver.json').unlink()
                    gate.require({p.name for p in target.secrets.iterdir()} == SECRET_FILES, 'restored key inventory mismatch')
                    for path in target.secrets.iterdir():
                        path.chmod(0o444)  # Synthetic Compose secrets for non-root services; parent is private.
                    saved = gate.document(restored / 'checkpoint.json')
                    gate.require(saved['snapshot'] == checkpoint and saved['queues'] == queues and
                                 saved['rabbitImage'] == source.rabbit_image and saved['node'] == policy['rabbitNode'], 'incompatible or mismatched checkpoint bundle')
                    target.activate()
                    target.validate()
                    target.compose('create', 'rabbitmq')
                    if mode == 'matched':
                        target.broker_archive(restored, 'unpack')
                    target.infrastructure()
                    # Restore exact definitions after the unchanged bootstrap job
                    # (which can re-hash the app password). In mismatch mode this
                    # is deliberately definitions-only, with no queue store.
                    target.compose('cp', str(restored / 'definitions.json'), 'rabbitmq:/tmp/recovery-definitions.json')
                    target.compose('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'import_definitions', '/tmp/recovery-definitions.json')
                    gate.require(canonical_definitions(target.probe_json('definitions')) == canonical_definitions(definitions),
                                 'restored broker definitions/credentials differ')
                    target.phase('empty database restore and full data/schema checkpoint ' + mode)
                    gate.require(target.sql("SELECT COUNT(*) FROM pg_tables WHERE schemaname='public'") == b'0', 'restore refuses nonempty target database')
                    target.compose('cp', str(restored / 'database.dump'), 'postgres:/tmp/recovery.dump')
                    target.compose('exec', '-T', 'postgres', 'pg_restore', '-U', 'flowpay_stage', '-d', 'flowpay_stage',
                                   '--no-owner', '--no-acl', '--exit-on-error', '/tmp/recovery.dump')
                    gate.require(target.snapshot() == checkpoint, 'all-table/history/sequence checkpoint differs after restore')
                    gate.write_json(target.fixtures / 'receiver.json', client_state)
                    target.probe_json('restore-control')
                    target.application(False, False)
                    gate.require(target.snapshot() == checkpoint, 'quiescent startup changed restored checkpoint')
                    target.phase('restored JWT API replay and checkpoint reconciliation ' + mode)
                    target.probe_json('jwt-check')
                    for case in ('history', 'pending-http', 'queued'):
                        target.probe_json('replay', case)
                    gate.require(unchanged_after_api_replay(checkpoint, target.snapshot()), 'API replay changed facts beyond last-use audit/forward sequences')
                    target.wait(lambda: all(v['consumers'] == 0 for v in target.queue_counts().values()), 'consumers stay disabled until reconciliation')
                    actual = target.queue_counts()
                    blocked = False
                    try:
                        reconcile(queues, actual, policy)
                    except gate.GateError:
                        blocked = True
                    gate.require(blocked == (mode == 'mismatch'), 'checkpoint mismatch was not detected/blocked')
                    if mode == 'mismatch':
                        gate.require(reconcile(queues, actual, policy, approve=True), 'reviewed mismatch approval absent')
                        gate.require(unchanged_after_api_replay(checkpoint, target.snapshot()), 'mismatch handling mutated financial/outbox history')
                    # First enable consumers, then explicit original-ID replay only
                    # for the approved mismatch case. Never reset Outbox statuses.
                    target.phase('resume original work and audited drain ' + mode)
                    target.application(True, True)
                    if mode == 'mismatch':
                        target.duplicate_replay_for_recovery()
                    target.drain()
                    audit = target.recovery_audit(3)
                    duration = time.monotonic() - start
                    rpo = time.time() - snapshot_time
                    gate.require(duration <= policy['rtoSeconds'] and rpo <= policy['rpoSeconds'], 'predeclared recovery target missed')
                    result = {'name': mode, 'status': 'passed', 'restoreSeconds': duration, 'snapshotAgeAtRecoverySeconds': rpo,
                              'checkpointEqualityAllTablesAndSequences': True, 'checkpointMismatchBlocked': blocked,
                              'financialConstraintColumnAndIndexEquality': True,
                              'checkExpressionsMatchPreLossSameDumpSchemaReference': True,
                              'retainedSourceTraceIdentity': True,
                              'operatorApprovedOriginalEventReplay': mode == 'mismatch', 'audit': audit}
                    target.phase('post-recovery duplicate, key failure and restart controls ' + mode)
                    result['duplicates'] = target.duplicate_control()
                    if mode == 'matched':
                        result['keyControls'] = target.key_controls()
                    target.stop_app()
                    target.application(True, True)
                    for case in ('history', 'pending-http', 'queued'):
                        target.probe_json('replay', case)
                    target.drain()
                    target.recovery_audit(3 if mode == 'mismatch' else 4)
                    target.cleanup()
                    contexts.remove(target)
                    evidence['cases'].append(result)
            finally:
                cleanup_failed = False
                for context in reversed(contexts):
                    try:
                        context.activate()
                        context.cleanup()
                    except Exception:
                        cleanup_failed = True
                contexts.clear()
                evidence['ownedSyntheticCleanup'] = 'failed' if cleanup_failed else 'passed'
                gate.require(not cleanup_failed, 'owned lab cleanup incomplete; inspect only recorded project labels')
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
    gate.require(fingerprint() == digest, 'recovery workload changed during acceptance')
    evidence.update(status='passed', ownedSyntheticCleanup='passed', completedAt=dt.datetime.now(dt.timezone.utc).isoformat())


def main():
    path = gate.SAFE / 'backup-restore.json'
    path.unlink(missing_ok=True)
    evidence = {'status': 'incomplete', 'timestamp': dt.datetime.now(dt.timezone.utc).isoformat()}
    try:
        run(evidence)
    except gate.GateError as error:
        evidence.update(status='failed', reason=str(error))
    except Exception as error:
        kind = type(error).__name__
        kind = kind if kind in ('KeyError', 'TypeError', 'ValueError', 'FileNotFoundError', 'PermissionError') else 'internal error'
        evidence.update(status='failed', reason='owned recovery failed (' + kind + '); raw credentials/SQL/body/diagnostics withheld')
    gate.write_json(path, evidence)
    print('backup-restore: ' + evidence['status'] + ' (safe evidence: target/ci-safe/backup-restore.json)')
    return 0 if evidence['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
