"""Private API/receiver/telemetry probes. Never export credentials or bodies."""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import re
from pathlib import Path
import secrets
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def http(url, method='GET', body=None, headers=None, timeout=15):
    request = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', **(headers or {})})
    context = ssl.create_default_context(cafile='/run/secrets/edge-cert.pem') if url.startswith('https:') else None
    try:
        with urllib.request.urlopen(request, timeout=timeout, context=context) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    except (OSError, TimeoutError):
        return 0, None
    try:
        envelope = json.loads(raw) if raw else None
    except ValueError:
        envelope = None
    return status, envelope


def expect(result, status):
    actual, body = result
    assert actual == status, 'owned status expected ' + str(status) + ', got ' + str(actual)
    return body


def control(body=None):
    return expect(http('http://receiver:8081/', 'GET' if body is None else 'POST', body), 200 if body is None else 204)


def api(path, method='GET', body=None, headers=None):
    return http('https://edge:8443/api/v1' + path, method, body, headers)


def wait(check, label, seconds=150):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(1)
    raise AssertionError('owned deadline: ' + label)


def auth(state, case, operation):
    return {'Authorization': 'Bearer ' + state['key'], 'Idempotency-Key': 'drill-' + state['suffix'] + '-' + case + '-' + operation}


def operation(path, method, body, headers, expected, concurrent=False):
    if concurrent:
        with ThreadPoolExecutor(max_workers=2) as pool:
            values = list(pool.map(lambda _: api(path, method, body, headers), range(2)))
        successes = [v for v in values if v[0] == expected]
        assert successes, 'owned concurrent request must commit at least once'
        logical = successes[0][1]['data']
        for status, envelope in values:
            assert status == expected or (status == 409 and envelope.get('code') == 'IDEMPOTENCY_REQUEST_IN_PROGRESS'), 'owned unexpected concurrency response'
            if status == expected:
                assert envelope['data'] == logical, 'owned concurrent replay differs'
    result = expect(api(path, method, body, headers), expected)['data']
    assert expect(api(path, method, body, headers), expected)['data'] == result, 'owned replay differs'
    return result


def prepare(case, concurrent=False):
    state = control()['client']
    item = operation('/payment-intents', 'POST', {'amount': 100000, 'currency': 'VND'}, auth(state, case, 'create'), 201, concurrent)
    conflict = expect(api('/payment-intents', 'POST', {'amount': 100001, 'currency': 'VND'}, auth(state, case, 'create')), 409)
    assert conflict['code'] == 'IDEMPOTENCY_KEY_REUSED', 'owned missing conflict'
    state['cases'][case] = {'payment': item}
    control({'client': state})


def confirm(case, concurrent=False, once=False):
    state = control()['client']
    item = state['cases'][case]
    path = '/payment-intents/' + item['payment']['id'] + '/confirm'
    item['confirmation'] = (expect(api(path, 'POST', headers=auth(state, case, 'confirm')), 200)['data'] if once else
                            operation(path, 'POST', None, auth(state, case, 'confirm'), 200, concurrent))
    assert item['confirmation']['paymentStatus'] == 'SUCCEEDED', 'owned simulator confirmation failed'
    control({'client': state})


def refund(case, concurrent=False):
    state = control()['client']
    item = state['cases'][case]
    item['refund'] = operation('/payment-intents/' + item['payment']['id'] + '/refunds', 'POST', {'amount': 1000},
                               auth(state, case, 'refund'), 201, concurrent)
    assert item['refund']['status'] == 'SUCCEEDED', 'owned simulator refund failed'
    control({'client': state})


def query():
    state = control()['client']
    return api('/payment-intents?page=0&size=20', headers={'Authorization': 'Bearer ' + state['key']})


def prometheus(expression):
    body = expect(http('http://prometheus:9090/api/v1/query?' + urllib.parse.urlencode({'query': expression})), 200)
    return body['data']['result']


def main(mode, case):
    if mode == 'setup':
        suffix = secrets.token_hex(12)
        email, password = 'drill-' + suffix + '@example.invalid', secrets.token_urlsafe(24)
        marker = {'X-FlowPay-CSRF': '1'}
        expect(api('/auth/register', 'POST', {'email': email, 'password': password, 'firstName': 'Drill', 'lastName': 'Fixture', 'merchantName': 'Owned resilience fixture'}, marker), 201)
        token = expect(api('/auth/login', 'POST', {'email': email, 'password': password}, marker), 200)['data']['accessToken']
        dashboard = {'Authorization': 'Bearer ' + token}
        key = expect(api('/merchant/api-keys', 'POST', {'name': 'Owned resilience fixture'}, dashboard), 201)['data']['key']
        endpoint = expect(api('/merchant/webhook-endpoints', 'POST', {'url': 'https://11.203.0.10:8443/drill',
                           'events': ['payment.succeeded', 'refund.succeeded']}, dashboard), 201)['data']
        control({'keys': {'/drill': endpoint['secret']}, 'client': {'key': key, 'suffix': suffix, 'cases': {}}})
    elif mode in ('workflow', 'concurrent'):
        prepare(case, mode == 'concurrent')
        confirm(case, mode == 'concurrent')
        refund(case, mode == 'concurrent')
    elif mode in ('prepare', 'confirm', 'refund'):
        {'prepare': prepare, 'confirm': confirm, 'refund': refund}[mode](case)
    elif mode == 'confirm-once':
        confirm(case, once=True)
    elif mode == 'replay':
        state = control()['client']
        item = state['cases'][case]
        path = '/payment-intents/' + item['payment']['id']
        assert expect(api(path + '/confirm', 'POST', headers=auth(state, case, 'confirm')), 200)['data'] == item['confirmation'], 'owned recovered confirmation replay differs'
        assert expect(api(path + '/refunds', 'POST', {'amount': 1000}, auth(state, case, 'refund')), 201)['data'] == item['refund'], 'owned recovered refund replay differs'
    elif mode == 'ready':
        wait(lambda: http('https://edge:8443/readyz')[0] == 200, 'TLS readiness')
    elif mode == 'database-down':
        with ThreadPoolExecutor(max_workers=2) as pool:
            edge = pool.submit(http, 'https://edge:8443/readyz', timeout=40)
            direct = pool.submit(http, 'http://app:8080/readyz', timeout=40)
            edge_status, direct_status = edge.result()[0], direct.result()[0]
        live_status = http('https://edge:8443/livez')[0]
        assert direct_status == 503, 'owned direct DB readiness expected 503, got ' + str(direct_status)
        assert edge_status in (503, 504), 'owned edge DB readiness expected 503 or 504, got ' + str(edge_status)
        assert live_status == 200, 'owned DB outage liveness expected 200, got ' + str(live_status)
        return {'applicationReadiness': direct_status, 'edgeReadiness': edge_status, 'liveness': live_status, 'financialRequestsOffered': 0}
    elif mode == 'query':
        expect(query(), 200)
    elif mode == 'available-query':
        def succeeds():
            status, body = query()
            assert status in (0, 200, 502, 503, 504), 'owned unexpected survivor query response'
            if status != 200:
                return False
            assert isinstance(body.get('data'), list) and body.get('meta'), 'owned survivor query contract failed'
            return True
        wait(succeeds, 'surviving replica business query')
        return {'survivorBusiness200': True}
    elif mode == 'replicas':
        cutoff = time.time()
        for _ in range(12):
            expect(query(), 200)
        fresh = 'up{job="flowpay"} == 1 and (timestamp(up{job="flowpay"}) >= ' + str(cutoff) + ')'
        wait(lambda: len(prometheus(fresh)) == 2, 'two new post-probe replica scrapes')
        wait(lambda: len(prometheus('sum by(instance)(http_server_requests_seconds_count{job="flowpay",uri=~"/api/v1/.*"}) > 0')) == 2,
             'both replicas serve business HTTP')
        return {'healthyScrapedReplicas': 2, 'businessHttpReplicas': 2, 'postProbeScrapesFresh': True}
    elif mode == 'monitoring-down':
        targets = ('http://prometheus:9090/-/ready', 'http://grafana:3000/api/health',
                   'http://collector:8888/metrics', 'http://tempo:3200/ready')
        assert all(http(url, timeout=3)[0] == 0 for url in targets), 'owned monitoring listener still reachable during outage'
        return {'stoppedTelemetryListeners': 4}
    elif mode == 'duplicates':
        password = Path('/run/secrets/RABBITMQ_ADMIN_PASSWORD').read_text().strip()
        headers = {'Authorization': 'Basic ' + base64.b64encode(('flowpay_bootstrap:' + password).encode()).decode()}
        envelopes = json.loads(Path('/fixtures/duplicates.json').read_text())
        for envelope in envelopes:
            payload = {'routing_key': envelope['eventType'], 'payload_encoding': 'string', 'payload': json.dumps(envelope),
                       'properties': {'message_id': envelope['eventId'], 'content_type': 'application/json', 'delivery_mode': 2}}
            assert expect(http('http://rabbitmq:15672/api/exchanges/%2F/flowpay.events/publish', 'POST', payload, headers), 200)['routed'], 'owned replay not routed'
        def drained():
            queues = expect(http('http://rabbitmq:15672/api/queues/%2F', headers=headers), 200)
            return bool(queues) and all(q.get('messages', 1) == 0 for q in queues)
        wait(drained, 'original-event duplicate queue drain')
        return {'originalEnvelopesRepublished': len(envelopes), 'queuesEmptyIncludingDlq': True}
    elif mode == 'traffic':
        counts = {'success': 0, 'transient': 0}
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline and not control()['trafficStop']:
            status, body = query()
            assert status in (0, 200, 502, 503, 504), 'owned unexpected rolling response'
            if status == 200:
                assert isinstance(body.get('data'), list) and body.get('meta'), 'owned query contract failed'
            counts['success' if status == 200 else 'transient'] += 1
            time.sleep(.25)
        assert control()['trafficStop'] and counts['success'] > 0, 'owned rolling traffic deadline/no successes'
        return counts
    else:
        raise AssertionError('owned unknown resilience probe')
    return {'passed': True}


if __name__ == '__main__':
    try:
        print(json.dumps(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 'fixture')))
    except AssertionError as error:
        message = str(error)
        print('FAIL: ' + (message if re.fullmatch(r'owned [A-Za-z0-9 /:;,.()_-]{1,180}', message) else 'owned resilience assertion failed'))
        sys.exit(1)
    except Exception:
        print('FAIL: owned resilience probe ' + sys.argv[1] + '; raw diagnostics withheld')
        sys.exit(1)
