"""P8-T17: read-only, sanitized triage and runbook contract checks. No repair API.

Docker administrator access is required. Only explicit UUID staging projects;
never load .env, execute arbitrary SQL, fetch messages or output raw diagnostics.
"""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import sys

SPEC = importlib.util.spec_from_file_location('recovery', Path(__file__).resolve().parents[1] / 'backup/backup.py')
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)
gate, ROOT = recovery.gate, recovery.ROOT
HERE = ROOT / 'ops/runbooks'
DOCS = ('docs/operations-runbook.md', 'docs/release-checklist.md')
COUNTS = {'unknownTransactions', 'processingRefunds', 'processingIdempotency', 'refundInvariantViolations',
          'unbalancedPostings', 'duplicatePostings', 'outboxUnpublished', 'webhookDead', 'expiredDeliveryLeases',
          'openDeliveryAttempts', 'payments', 'refunds', 'postings', 'webhookEvents', 'delivered'}
QUEUES = {'flowpay.ledger.events', 'flowpay.webhook.events', 'flowpay.ledger.events.dlq', 'flowpay.webhook.events.dlq'}
METERS = {'process_cpu_usage', 'jvm_memory_used_bytes', 'jvm_memory_max_bytes',
          'hikaricp_connections_active', 'hikaricp_connections_max', 'hikaricp_connections_pending',
          'flowpay_metrics_sample_age_seconds'}


def executable_digest():
    value = hashlib.sha256()
    for path in sorted(HERE.iterdir()):
        if path.suffix in ('.py', '.sql', '.yaml'):
            value.update(path.name.encode() + b'\0' + path.read_bytes().replace(b'\r\n', b'\n') + b'\0')
    return value.hexdigest()


def project_name(value):
    gate.require(isinstance(value, str) and re.fullmatch(r'flowpay-stage-[a-f0-9]{32}', value), 'explicit UUID staging project required')
    return value


def owned(project, value, service):
    project_name(project)
    labels = value.get('Config', {}).get('Labels', {})
    gate.require(service in recovery.staging.CORE and re.fullmatch(r'[a-f0-9]{64}', value.get('Id', '')) and
                 labels.get('com.docker.compose.project') == project and labels.get('com.docker.compose.service') == service,
                 'foreign container/service identity; refusing diagnostic exec')
    return value['Id']


def containers(project):
    project_name(project)
    raw, _ = gate.run(['docker', 'container', 'ls', '--all', '--quiet', '--filter', 'label=com.docker.compose.project=' + project])
    ids = raw.decode().split()
    gate.require(ids and all(re.fullmatch(r'[a-f0-9]{12,64}', value) for value in ids), 'explicit staging project has no inspectable containers')
    values = json.loads(gate.run(['docker', 'inspect'] + ids)[0])
    result = {}
    for value in values:
        service = value.get('Config', {}).get('Labels', {}).get('com.docker.compose.service')
        if service in recovery.staging.CORE:
            owned(project, value, service)
            result.setdefault(service, []).append(value)
    gate.require(all(len(result.get(name, [])) == 1 for name in ('postgres', 'rabbitmq')) and result.get('app'),
                 'one owned DB/broker and at least one owned application required')
    return result


def execute(project, value, service, arguments):
    identity = owned(project, value, service)
    fresh = json.loads(gate.run(['docker', 'inspect', identity])[0])[0]
    gate.require(owned(project, fresh, service) == identity and fresh['State']['Running'] and not fresh['State'].get('Paused'),
                 'diagnostic target unavailable or ownership changed')
    return gate.run(['docker', 'exec', identity] + arguments, timeout=30)[0]


def durable_projection(value):
    gate.require(isinstance(value, dict) and set(value) == COUNTS and
                 all(type(v) is int and v >= 0 for v in value.values()), 'invalid durable count projection; raw rows withheld')
    return value


def queue_projection(value):
    gate.require(isinstance(value, list) and {item.get('name') for item in value} == QUEUES and len(value) == 4,
                 'broker queue inventory missing or unreviewed; raw metadata withheld')
    result = {}
    for item in value:
        counts = {name: item.get(name) for name in ('messages_ready', 'messages_unacknowledged', 'consumers')}
        gate.require(all(type(v) is int and v >= 0 for v in counts.values()), 'invalid broker count projection')
        result[item['name']] = counts
    return result


def metric_projection(raw):
    # No arbitrary label/header/body propagation; keep per-replica gauges separate.
    result = {}
    for line in raw.decode().splitlines():
        match = re.fullmatch(r'([a-z_]+)(?:\{[^\n]*\})? ([0-9.eE+\-]+|NaN|\+Inf|-Inf)', line)
        if not match or match[1] not in METERS:
            continue
        if match[1].startswith('jvm_memory_') and 'area="heap"' not in line:
            continue
        number = float(match[2])
        result.setdefault(match[1], []).append(number if math.isfinite(number) and number >= 0 else None)
    gate.require(result, 'no allow-listed application metric samples')
    return result


def sql_text():
    text = (HERE / 'triage.sql').read_text(encoding='utf-8')
    plain = re.sub(r'--[^\n]*', '', text).strip()
    gate.require(plain.startswith('SELECT ') and plain.count(';') == 1 and plain.endswith(';') and
                 not re.search(r'\b(UPDATE|INSERT|DELETE|ALTER|DROP|TRUNCATE|COPY|CALL|DO|SET|CREATE)\b', plain, re.I),
                 'triage SQL must remain a single fixed SELECT')
    return text


def collect(project):
    states = containers(project)
    report = {'scope': 'read-only staging diagnosis; not a health or financial-outcome certificate',
              'projectIdentityVerified': True, 'durable': {'status': 'unavailable'}, 'broker': {'status': 'unavailable'}, 'replicas': []}
    try:
        raw = execute(project, states['postgres'][0], 'postgres',
                      ['env', 'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=2000 -c lock_timeout=1000',
                       'psql', '-U', 'flowpay_stage', '-d', 'flowpay_stage', '-v', 'ON_ERROR_STOP=1', '-Atc', sql_text()])
        report['durable'] = {'status': 'captured', 'counts': durable_projection(json.loads(raw))}
    except gate.GateError:
        pass  # Missing evidence is unavailable, NEVER an invented zero.
    try:
        raw = execute(project, states['rabbitmq'][0], 'rabbitmq',
                      ['rabbitmqctl', '-q', 'list_queues', 'name', 'messages_ready', 'messages_unacknowledged', 'consumers', '--formatter=json'])
        report['broker'] = {'status': 'captured', 'queues': queue_projection(json.loads(raw))}
    except gate.GateError:
        pass
    for value in states['app']:
        item = {'running': bool(value['State']['Running']), 'oomKilled': bool(value['State']['OOMKilled'])}
        for route in ('livez', 'readyz'):
            item[route] = 'unavailable'
            try:
                raw = execute(project, value, 'app', ['curl', '--silent', '--fail', '--max-time', '5', 'http://127.0.0.1:8080/' + route])
                status = json.loads(raw).get('status')
                if status in ('UP', 'DOWN', 'OUT_OF_SERVICE', 'UNKNOWN'):
                    item[route] = status
            except gate.GateError:
                pass
        item['metrics'] = {'status': 'unavailable'}
        try:
            raw = execute(project, value, 'app', ['curl', '--silent', '--fail', '--max-time', '5', 'http://127.0.0.1:9091/actuator/prometheus'])
            item['metrics'] = {'status': 'captured', 'gauges': metric_projection(raw)}
        except gate.GateError:
            pass
        report['replicas'].append(item)
    return report


def headings(path):
    return {re.sub(r'[^\w\- ]', '', value.lower()).replace(' ', '-') for value in re.findall(r'^#{1,6}\s+(.+)$', path.read_text(encoding='utf-8'), re.M)}


def check():
    links = 0
    for relative in DOCS:
        path = ROOT / relative
        content = path.read_text(encoding='utf-8')
        gate.require(not re.search(r'\b(TODO|TBD|FIXME)\b', content), 'runbook placeholder is not operational guidance')
        for target in re.findall(r'\[[^\]]+\]\(([^)]+)\)', content):
            if target.startswith(('https://', 'http://')):
                continue
            filename, _, fragment = target.partition('#')
            resolved = (path.parent / filename).resolve() if filename else path
            gate.require(resolved.is_relative_to(ROOT) and resolved.is_file(), 'runbook local link missing or escaping repository')
            gate.require(not fragment or fragment in headings(resolved), 'runbook heading anchor missing')
            links += 1
    sql_text()
    api = (ROOT / 'docs/api-contract.md').read_text(encoding='utf-8')
    gate.require('POST /api/v1/merchant/webhook-deliveries/{deliveryId}/retry' in api and '202 Accepted' in api and
                 'dashboard JWT authentication' in api, 'manual retry contract drift')
    controller = (ROOT / 'src/main/java/com/flowpay/backend/webhook/api/WebhookDeliveryController.java').read_text(encoding='utf-8')
    config = (ROOT / 'src/main/resources/application.yaml').read_text(encoding='utf-8')
    stage = (ROOT / 'ops/staging/compose.yaml').read_text(encoding='utf-8')
    gate.require('@PostMapping("/{deliveryId}/retry")' in controller and '@ResponseStatus(HttpStatus.ACCEPTED)' in controller and
                 'DashboardPrincipal principal' in controller and 'stop_grace_period: 120s' in stage and
                 'WEBHOOK_DELIVERY_RETRY_DELAYS:10s,30s,2m,10m,1h' in config and
                 'WEBHOOK_DELIVERY_RETRY_JITTER_MAX:0.20' in config and 'WEBHOOK_DELIVERY_WORKER_LEASE_TIMEOUT:30s' in config,
                 'runbook retry/auth/drain configuration drift')
    policy = gate.document(ROOT / 'ops/backup/policy.json')
    recovery.validate_policy(policy)
    gate.require(gate.document(ROOT / 'ops/ci/exceptions.json') == {'exceptions': []}, 'no scan exception authority granted by runbooks')
    return {'status': 'passed', 'localLinks': links, 'documents': len(DOCS), 'fixedReadOnlySql': True, 'sourceConfigurationMatched': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('check', 'triage', 'rehearse'))
    parser.add_argument('--project')
    args = parser.parse_args()
    try:
        if args.command == 'check':
            gate.require(args.project is None, 'check does not accept a target project')
            report = check()
        elif args.command == 'triage':
            report = collect(project_name(args.project))
        else:
            gate.require(args.project is None, 'rehearsal always creates its own project; refusing target adoption')
            spec = importlib.util.spec_from_file_location('rehearsal', HERE / 'rehearse.py')
            rehearsal = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(rehearsal)
            report = rehearsal.run()
        print(json.dumps(report, indent=2, allow_nan=False))
        if args.command == 'triage':
            return 0 if report['durable']['status'] == report['broker']['status'] == 'captured' and all(
                v['livez'] == v['readyz'] == 'UP' and v['metrics']['status'] == 'captured' and not v['oomKilled'] for v in report['replicas']) else 2
        return 0
    except gate.GateError as error:
        print('runbooks: failed: ' + str(error))
    except Exception:
        print('runbooks: failed; raw credentials/SQL/diagnostics withheld')
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
