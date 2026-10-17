"""Bounded synthetic TLS subscriber: exact-body HMAC, slow 2xx, first-attempt503.

Only numeric aggregate observations are exported. Signing keys, event IDs,
payload digests and the synthetic API credential stay inside receiver RAM.
"""
import datetime as dt
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import socket
import ssl
import threading
import time

LOCK = threading.Lock()
STATE = {'keys': {}, 'client': None, 'phase': 'setup', 'invalid': False, 'overflow': False}
SEEN = {}
PHASES = {}
MAX_IDENTITIES = 100_000
MAX_SAMPLES = 20_000


def summary(values):
    if not values:
        return {'count': 0, 'p95': None, 'p99': None, 'max': None}
    ordered = sorted(values)
    return {'count': len(values), 'p95': ordered[math.ceil(len(values) * .95) - 1],
            'p99': ordered[math.ceil(len(values) * .99) - 1], 'max': ordered[-1]}


def safe_stats():
    with LOCK:
        return {'invalid': STATE['invalid'], 'overflow': STATE['overflow'],
                'identities': len(SEEN), 'phases': {name: {path: {
                    'requests': row['requests'], 'failures503': row['failures503'], 'delivered': row['delivered'],
                    'lagSeconds': summary(row['lags']), 'handlerSeconds': summary(row['durations']),
                } for path, row in paths.items()} for name, paths in PHASES.items()}}


class Control(BaseHTTPRequestHandler):
    def log_message(self, *arguments):
        pass

    def do_GET(self):
        if self.path == '/stats':
            body = safe_stats()
        else:
            with LOCK:
                body = {'client': STATE['client']}
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps(body, allow_nan=False).encode())

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        with LOCK:
            if 'keys' in data:
                STATE['keys'].update(data['keys'])
            if 'client' in data:
                STATE['client'] = data['client']
            if 'phase' in data:
                phase = data['phase']
                if phase not in ('setup', 'sanity', 'calibration', 'peak_probe', 'stress_probe',
                                 'warmup', 'steady', 'peak', 'stress', 'soak'):
                    self.send_error(400)
                    return
                STATE['phase'] = phase
        self.send_response(204)
        self.end_headers()


class Merchant(Control):
    def do_GET(self):
        self.send_response(204 if self.path == '/health' else 404)
        self.end_headers()

    def do_POST(self):
        started = time.monotonic()
        try:
            raw = self.rfile.read(int(self.headers['Content-Length']))
            body = json.loads(raw)
            timestamp, signature = self.headers['FlowPay-Signature'].split(',v1=')
            timestamp = timestamp.removeprefix('t=')
            identity = self.path + ':' + body['id']
            digest = hashlib.sha256(raw).digest()
            with LOCK:
                expected = hmac.new(STATE['keys'][self.path].encode(), timestamp.encode() + b'.' + raw, hashlib.sha256).hexdigest()
                valid = hmac.compare_digest(expected, signature) and abs(int(timestamp) - time.time()) < 60
                valid &= self.headers['FlowPay-Event-Id'] == body['id']
                valid &= not any(key.lower() in ('authorization', 'x-merchant-id', 'traceparent', 'baggage') for key in self.headers)
                previous = SEEN.get(identity)
                valid &= previous is None or previous['digest'] == digest
                STATE['invalid'] |= not valid
                if previous is None:
                    if len(SEEN) >= MAX_IDENTITIES:
                        STATE['overflow'] = True
                        raise ValueError('Bounded receiver capacity exceeded')
                    previous = SEEN[identity] = {'digest': digest, 'attempts': 0, 'delivered': False}
                previous['attempts'] += 1
                phase = STATE['phase']
                row = PHASES.setdefault(phase, {}).setdefault(self.path,
                    {'requests': 0, 'failures503': 0, 'delivered': 0, 'lags': [], 'durations': []})
                row['requests'] += 1
                fail = self.path == '/flaky' and previous['attempts'] == 1
                if fail:
                    row['failures503'] += 1
            if self.path == '/slow':
                time.sleep(.75)
            code = 400 if not valid else 503 if fail else 204
            with LOCK:
                if code == 204 and not previous['delivered']:
                    previous['delivered'] = True
                    row['delivered'] += 1
                    occurred = dt.datetime.fromisoformat(body['createdAt'].replace('Z', '+00:00')).timestamp()
                    if len(row['lags']) >= MAX_SAMPLES:
                        STATE['overflow'] = True
                    else:
                        row['lags'].append(max(0, time.time() - occurred))
                if len(row['durations']) < MAX_SAMPLES:
                    row['durations'].append(time.monotonic() - started)
                else:
                    STATE['overflow'] = True
            self.send_response(code)
            self.end_headers()
        except OSError:
            pass
        except Exception:
            with LOCK:
                STATE['invalid'] = True
            self.close_connection = True


class DualStack(ThreadingHTTPServer):
    address_family = socket.AF_INET6


if __name__ == '__main__':
    control = ThreadingHTTPServer(('0.0.0.0', 8081), Control)
    threading.Thread(target=control.serve_forever, daemon=True).start()
    merchant = DualStack(('::', 8443), Merchant)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain('/run/secrets/cert.pem', '/run/secrets/key.pem')
    merchant.socket = context.wrap_socket(merchant.socket, server_side=True)
    merchant.serve_forever()
