"""Staging API and private telemetry probes. Never print bodies or credentials."""
import base64
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import sys
import time
import urllib.error
import urllib.request

TLS = ssl.create_default_context(cafile='/run/secrets/edge-cert.pem')
EVENTS = ['payment.processing', 'payment.succeeded', 'payment.failed', 'refund.processing', 'refund.succeeded', 'refund.failed']


def failure_label(error):
    """Classify only fixed assertions; never publish inherited raw diagnostics."""
    message = str(error)
    fixed_owned = {'owned secure cookie policy failed', 'owned duplicate changed delivery state', 'owned unsupported phase'}
    if message in fixed_owned or re.fullmatch(r'owned status expected [0-9]{3}, got [0-9]{3}', message) or re.fullmatch(
            r'owned deadline: (readiness|success fanout|replica scrapes|duplicate broker drain)', message):
        return message
    fixed_monitoring = {
        'Grafana failed', 'Grafana dashboard provisioning did not become ready',
        'Dashboard provisioning differs from versioned JSON',
        'Grafana datasource provisioning did not become ready', 'Datasource not provisioned',
        'Required scrape targets unavailable', 'PromQL failed',
        'Operational alert provisioning incomplete', 'Rule evaluation failed',
        'Controlled business trace not retrievable', 'End-to-end trace ancestry is not yet exported',
        'Sensitive fixture reached trace storage', 'Grafana trace proxy not ready',
    }
    if message in fixed_monitoring:
        return 'owned monitoring: ' + message.lower()
    for prefix, label in (
            ('Expected metric not ingested: ', 'owned monitoring expected metric not ingested'),
            ('Dashboard query not ready: ', 'owned monitoring dashboard query failed'),
            ('Unexpected HTTP status on ', 'owned monitoring HTTP status mismatch')):
        if message.startswith(prefix):
            return label  # Do not echo expression/title/host/response/credential suffixes.
    return 'owned staging assertion failed'


def http(url, method='GET', body=None, headers=None, expected=200):
    request = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=15, context=TLS) as response:
            status, raw = response.status, response.read()
            if url.endswith('/auth/login') and status == 200:
                cookie = response.headers.get('Set-Cookie', '')
                assert 'Secure' in cookie and 'HttpOnly' in cookie and 'SameSite=Lax' in cookie, 'owned secure cookie policy failed'
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    assert status == expected, 'owned status expected ' + str(expected) + ', got ' + str(status)
    return json.loads(raw) if raw else None


def api(path, method='GET', body=None, headers=None, expected=200):
    return http('https://edge:8443/api/v1' + path, method, body, headers, expected)


def control(data=None):
    return http('http://receiver:8081/', 'GET' if data is None else 'POST', data, expected=200 if data is None else 204)


def wait(check, label, timeout=150):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, AssertionError):
            pass
        time.sleep(1)
    raise AssertionError('owned deadline: ' + label)


def ready():
    host = 'http://app:8080' if os.environ.get('STAGING_QUIESCED') == 'true' else 'https://edge:8443'
    wait(lambda: http(host + '/readyz')['status'] == 'UP', 'readiness')


def headers(state, operation):
    return {'Authorization': 'Bearer ' + state['key'], 'Idempotency-Key': 'stage-' + operation + '-' + state['suffix']}


def payment(state, outcome):
    create = headers(state, 'create-' + outcome)
    body = {'amount': 100000, 'currency': 'VND'}
    created = api('/payment-intents', 'POST', body, create, 201)['data']
    assert api('/payment-intents', 'POST', body, create, 201)['data'] == created
    assert api('/payment-intents', 'POST', {**body, 'amount': 100001}, create, 409)['code'] == 'IDEMPOTENCY_KEY_REUSED'
    confirm = headers(state, 'confirm-' + outcome)
    if outcome == 'SUCCESS':
        confirm['traceparent'] = '00-' + state['trace'] + '-' + secrets.token_hex(8) + '-01'
    path = '/payment-intents/' + created['id'] + '/confirm'
    status = 202 if outcome == 'UNKNOWN' else 200
    result = api(path, 'POST', headers=confirm, expected=status)['data']
    assert result['paymentStatus'] == {'SUCCESS': 'SUCCEEDED', 'DECLINED': 'FAILED', 'UNKNOWN': 'PROCESSING'}[outcome]
    assert api(path, 'POST', headers=confirm, expected=status)['data'] == result
    return {'id': created['id'], 'confirm': result, 'httpStatus': status}


def refund(state, outcome):
    path = '/payment-intents/' + state['SUCCESS']['id'] + '/refunds'
    status = 202 if outcome == 'UNKNOWN' else 201
    result = api(path, 'POST', {'amount': 1000}, headers(state, 'refund-' + outcome), status)['data']
    assert result['status'] == {'SUCCESS': 'SUCCEEDED', 'DECLINED': 'FAILED', 'UNKNOWN': 'PROCESSING'}[outcome]
    assert api(path, 'POST', {'amount': 1000}, headers(state, 'refund-' + outcome), status)['data'] == result
    return result


def main(mode):
    ready()
    if mode == 'ready':
        return
    if mode == 'setup':
        suffix = secrets.token_hex(8)
        email, password = 'stage-' + suffix + '@example.invalid', secrets.token_urlsafe(24)
        marker = {'X-FlowPay-CSRF': '1'}
        api('/auth/register', 'POST', {'email': email, 'password': password, 'firstName': 'Stage', 'lastName': 'Fixture',
            'merchantName': 'Owned staging fixture'}, marker, 201)
        jwt = api('/auth/login', 'POST', {'email': email, 'password': password}, marker)['data']['accessToken']
        dashboard = {'Authorization': 'Bearer ' + jwt}
        key = api('/merchant/api-keys', 'POST', {'name': 'Owned staging fixture'}, dashboard, 201)['data']['key']
        state = {'suffix': suffix, 'jwt': jwt, 'key': key, 'trace': secrets.token_hex(16), 'forbidden': [email, password, jwt, key]}
        for path, target in (('/v4', '11.203.0.10'), ('/v6', '[2606:4700:ffff::10]')):
            endpoint = api('/merchant/webhook-endpoints', 'POST', {'url': 'https://' + target + ':8443' + path, 'events': EVENTS}, dashboard, 201)['data']
            control({'keys': {path: endpoint['secret']}})
            state['forbidden'].append(endpoint['secret'])
        state['SUCCESS'] = payment(state, 'SUCCESS')
        state['SUCCESS']['refund'] = refund(state, 'SUCCESS')
        control({'client': state})
        wait(lambda: len([item for item in control()['receipts'].values() if item['path'] != '/legacy']) == 8, 'success fanout')
    elif mode in ('DECLINED', 'UNKNOWN'):
        state = control()['client']
        state[mode] = payment(state, mode)
        state[mode]['refund'] = refund(state, mode)
        control({'client': state})
    elif mode == 'monitoring':
        sys.path.insert(0, '/monitoring')
        import smoke
        state = control()['client']
        smoke.monitoring_fixture(state['trace'], state['forbidden'])
        wait(lambda: len(smoke.query('up{job="flowpay"} == 1')) == int(os.environ.get('STAGING_REPLICAS', '2')), 'replica scrapes')
    elif mode == 'replay':
        state = control()['client']
        assert api('/merchant/webhook-endpoints', headers={'Authorization': 'Bearer ' + state['jwt']})['data']
        for outcome in ('SUCCESS', 'DECLINED', 'UNKNOWN'):
            expected = state[outcome]
            assert api('/payment-intents/' + expected['id'] + '/confirm', 'POST', headers=headers(state, 'confirm-' + outcome),
                       expected=expected['httpStatus'])['data'] == expected['confirm']
            assert api('/payment-intents/' + state['SUCCESS']['id'] + '/refunds', 'POST', {'amount': 1000},
                       headers(state, 'refund-' + outcome), 202 if outcome == 'UNKNOWN' else 201)['data'] == expected['refund']
        wait(lambda: len(control()['receipts']) == int(os.environ['STAGING_DELIVERIES']), 'all source event deliveries')
        assert not control()['invalid'], 'owned immutable payload/signature invariant failed'
    elif mode == 'duplicates':
        password = Path('/run/secrets/RABBITMQ_ADMIN_PASSWORD').read_text().strip()
        auth = base64.b64encode(('flowpay_bootstrap:' + password).encode()).decode()
        broker_headers = {'Authorization': 'Basic ' + auth}
        before = control()['requests']
        for envelope in json.loads(Path('/fixtures/duplicates.json').read_text()):
            body = {'routing_key': envelope['eventType'], 'payload_encoding': 'string', 'payload': json.dumps(envelope),
                    'properties': {'message_id': envelope['eventId'], 'content_type': 'application/json', 'delivery_mode': 2}}
            assert http('http://rabbitmq:15672/api/exchanges/%2F/flowpay.events/publish', 'POST', body, broker_headers)['routed']
        def drained():
            queues = http('http://rabbitmq:15672/api/queues/%2F', headers=broker_headers)
            return all(item.get('messages', 1) == 0 for item in queues)
        wait(drained, 'duplicate broker drain')
        assert control()['requests'] == before and not control()['invalid'], 'owned duplicate changed delivery state'
    else:
        raise AssertionError('owned unsupported phase')


if __name__ == '__main__':
    try:
        main(sys.argv[1])
        print('PASS: owned staging phase ' + sys.argv[1])
    except AssertionError as error:
        print('FAIL: ' + failure_label(error))
        sys.exit(1)
    except Exception:
        print('FAIL: owned staging probe; raw body/credential/exception diagnostics withheld')
        sys.exit(1)
