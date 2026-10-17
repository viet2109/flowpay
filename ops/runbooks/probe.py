"""Rehearse documented dashboard diagnosis and invalid retry without raw output."""
import importlib.util
import json
from pathlib import Path
import secrets
import sys

BASE = Path(__file__).with_name('base.py')
if not BASE.is_file():
    BASE = Path(__file__).resolve().parents[1] / 'resilience/probe.py'
SPEC = importlib.util.spec_from_file_location('base', BASE)
base = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(base)
SPEC = importlib.util.spec_from_file_location('backup_probe', Path(__file__).resolve().parents[1] / 'backup/probe.py')
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)


def main(mode, case):
    if mode == 'queues':
        return backup.main(mode, case)
    if mode == 'setup':
        suffix, password = secrets.token_hex(12), secrets.token_urlsafe(24)
        email = 'runbook-' + suffix + '@example.invalid'
        marker = {'X-FlowPay-CSRF': '1'}
        base.expect(base.api('/auth/register', 'POST', {'email': email, 'password': password, 'firstName': 'Runbook',
                    'lastName': 'Fixture', 'merchantName': 'Owned operational rehearsal'}, marker), 201)
        token = base.expect(base.api('/auth/login', 'POST', {'email': email, 'password': password}, marker), 200)['data']['accessToken']
        headers = {'Authorization': 'Bearer ' + token}
        key = base.expect(base.api('/merchant/api-keys', 'POST', {'name': 'Owned runbook fixture'}, headers), 201)['data']['key']
        endpoint = base.expect(base.api('/merchant/webhook-endpoints', 'POST', {'url': 'https://11.203.0.10:8443/drill',
                     'events': ['payment.succeeded', 'refund.succeeded']}, headers), 201)['data']
        base.control({'keys': {'/drill': endpoint['secret']}, 'jwt': token, 'client': {'key': key, 'suffix': suffix, 'cases': {}}})
        return {'passed': True}
    if mode == 'delivery-triage':
        headers = {'Authorization': 'Bearer ' + base.control()['jwt']}
        values = base.expect(base.api('/merchant/webhook-deliveries?status=DELIVERED&page=0&size=20', headers=headers), 200)
        assert len(values['data']) == 2 and values['meta']['totalElements'] == 2
        for value in values['data']:
            path = '/merchant/webhook-deliveries/' + value['id']
            detail = base.expect(base.api(path, headers=headers), 200)['data']
            assert detail['status'] == 'DELIVERED' and detail['attempts']
            assert base.expect(base.api(path + '/retry', 'POST', headers=headers), 409)['code'] == 'WEBHOOK_INVALID_STATE'
        assert base.expect(base.api('/merchant/webhook-deliveries?status=DEAD', headers=headers), 200)['data'] == []
        return {'dashboardListAndDetail200': True, 'deliveredRetry409': True, 'deadListEmpty': True}
    return base.main(mode, case)


if __name__ == '__main__':
    try:
        print(json.dumps(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 'fixture')))
    except Exception:
        print('FAIL: owned runbook probe failed; raw response withheld')
        sys.exit(1)
