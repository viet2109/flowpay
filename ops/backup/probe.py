"""Private DR probes; credentials and source envelopes never reach public evidence."""
import importlib.util
import json
from pathlib import Path
import re
import sys

BASE = Path(__file__).with_name('base.py')
if not BASE.is_file():  # Same functions can be unit-tested on the host without Docker.
    BASE = Path(__file__).resolve().parents[1] / 'resilience/probe.py'
SPEC = importlib.util.spec_from_file_location('base', BASE)
base = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(base)


def broker(path, method='GET', body=None):
    import base64
    password = Path('/run/secrets/RABBITMQ_ADMIN_PASSWORD').read_text().strip()
    headers = {'Authorization': 'Basic ' + base64.b64encode(('flowpay_bootstrap:' + password).encode()).decode()}
    return base.expect(base.http('http://rabbitmq:15672/api/' + path, method, body, headers), 200)


def publication(record):
    envelope, parent = record['envelope'], record['traceparent']
    assert set(envelope) == {'eventId', 'eventType', 'aggregateType', 'aggregateId', 'occurredAt', 'payload'}
    headers = {'x-flowpay-event-type': envelope['eventType'], 'x-flowpay-aggregate-type': envelope['aggregateType'],
               'x-flowpay-aggregate-id': envelope['aggregateId']}
    if parent is not None:
        assert isinstance(parent, str) and re.fullmatch(r'00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]', parent)
        assert parent[3:35] != '0' * 32 and parent[36:52] != '0' * 16
        headers['traceparent'] = parent
    return {'routing_key': envelope['eventType'], 'payload_encoding': 'string', 'payload': json.dumps(envelope),
            'properties': {'message_id': envelope['eventId'], 'content_type': 'application/json', 'content_encoding': 'UTF-8',
                           'delivery_mode': 2, 'headers': headers}}


def main(mode, case):
    if mode == 'original-replay':
        records = json.loads(Path('/fixtures/duplicates.json').read_text())
        for record in records:
            assert broker('exchanges/%2F/flowpay.events/publish', 'POST', publication(record))['routed']
        base.wait(lambda: all(q.get('messages', 1) == 0 for q in broker('queues/%2F')), 'original-event queue drain')
        return {'originalEnvelopesRepublished': len(records), 'queuesEmptyIncludingDlq': True, 'originalTransportHeadersRetained': True}
    if mode == 'queues':
        values = broker('queues/%2F')
        return {v['name']: {'ready': v.get('messages_ready', 0), 'unacked': v.get('messages_unacknowledged', 0),
                            'consumers': v.get('consumers', 0)} for v in values}
    if mode == 'definitions':
        # Password hashes are private; runner places this only inside encrypted bundle.
        return broker('definitions')
    if mode == 'jwt-save':
        # Base setup does not retain JWT; obtain another synthetic identity for key-continuity proof.
        import secrets
        suffix, password = secrets.token_hex(12), secrets.token_urlsafe(24)
        email = 'restore-' + suffix + '@example.invalid'
        marker = {'X-FlowPay-CSRF': '1'}
        base.expect(base.api('/auth/register', 'POST', {'email': email, 'password': password, 'firstName': 'Restore',
                        'lastName': 'Fixture', 'merchantName': 'Restore JWT fixture'}, marker), 201)
        token = base.expect(base.api('/auth/login', 'POST', {'email': email, 'password': password}, marker), 200)['data']['accessToken']
        base.control({'jwt': token})
        return {'passed': True}
    if mode == 'jwt-check':
        token = base.control()['jwt']
        base.expect(base.api('/merchant', headers={'Authorization': 'Bearer ' + token}), 200)
        return {'passed': True}
    if mode == 'restore-control':
        base.control(json.loads(Path('/fixtures/receiver.json').read_text()))
        return {'passed': True}
    return base.main(mode, case)


if __name__ == '__main__':
    try:
        print(json.dumps(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 'fixture')))
    except AssertionError as error:
        message = str(error)
        print('FAIL: ' + (message if re.fullmatch(r'owned [A-Za-z0-9 /:;,.()_-]{1,180}', message) else 'owned backup assertion failed'))
        sys.exit(1)
    except Exception:
        print('FAIL: owned backup probe failed; raw diagnostics withheld')
        sys.exit(1)
