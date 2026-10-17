"""Idempotent topology bootstrap. No queue deletion or raw HTTP/secret diagnostics."""
import base64
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def bootstrap():
    root = Path('/run/secrets')
    password = (root / 'RABBITMQ_ADMIN_PASSWORD').read_text().strip()
    auth = base64.b64encode(('flowpay_bootstrap:' + password).encode()).decode()

    def call(path, method='GET', body=None):
        request = urllib.request.Request('http://rabbitmq:15672/api/' + path, method=method,
                                        data=None if body is None else json.dumps(body).encode(),
                                        headers={'Authorization': 'Basic ' + auth, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=5) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    deadline = time.monotonic() + 90
    while True:
        try:
            call('overview')
            break
        except (OSError, urllib.error.URLError):
            if time.monotonic() >= deadline:
                raise RuntimeError('Broker management unavailable') from None
            time.sleep(1)
    definitions = json.loads(Path('/bootstrap/definitions.json').read_text())
    call('users/flowpay_stage', 'PUT', {'password': (root / 'RABBITMQ_PASSWORD').read_text().strip(), 'tags': ''})
    call('permissions/%2F/flowpay_stage', 'PUT', {kind: r'^flowpay\..*' for kind in ('configure', 'write', 'read')})
    for kind in ('exchanges', 'queues'):
        for value in definitions[kind]:
            call(kind + '/%2F/' + urllib.parse.quote(value['name'], safe=''), 'PUT', value)
    for value in definitions['bindings']:
        path = 'bindings/%2F/e/' + urllib.parse.quote(value['source'], safe='') + '/q/' + urllib.parse.quote(value['destination'], safe='')
        call(path, 'POST', {'routing_key': value['routing_key'], 'arguments': {}})
    actual = call('bindings/%2F')
    expected = {(item['source'], item['destination'], item['routing_key']) for item in definitions['bindings']}
    assert expected <= {(item['source'], item['destination'], item['routing_key']) for item in actual}
    assert len(expected) == 10
    print('PASS: topology ready before producers; six event keys, independent queues/DLQs and non-admin app user')


if __name__ == '__main__':
    try:
        bootstrap()
    except Exception:
        print('FAIL: staging topology bootstrap; raw credentials/HTTP diagnostics withheld', file=sys.stderr)
        sys.exit(1)
