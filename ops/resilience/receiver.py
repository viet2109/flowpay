"""Disposable TLS subscriber with one-shot real slow/held HTTP barriers.

Secrets, public IDs, exact-body digests and replay snapshots stay in RAM.
Only safe_stats() is reportable; the control port is private to the fixture.
"""
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import socket
import ssl
import threading
import time

LOCK = threading.Lock()
RELEASE = threading.Event()
STATE = {'keys': {}, 'client': None, 'arm': 'normal', 'selected': None,
         'held': False, 'invalid': False, 'trafficStop': False, 'released': 0}
SEEN = {}


def safe_stats():
    with LOCK:
        return {'identities': len(SEEN), 'requests': sum(v['attempts'] for v in SEEN.values()),
                'duplicateRequests': sum(v['attempts'] - 1 for v in SEEN.values()),
                'invalid': STATE['invalid'], 'held': STATE['held'], 'released': STATE['released']}


class Control(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == '/stats':
            body = safe_stats()
        else:
            with LOCK:
                body = {'client': STATE['client'], 'held': STATE['held'],
                        'selected': STATE['selected'], 'trafficStop': STATE['trafficStop']}
        raw = json.dumps(body).encode()
        self.send_response(200)
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        size = int(self.headers.get('Content-Length', '0'))
        if not 0 < size <= 32768:
            self.send_error(400)
            return
        body = json.loads(self.rfile.read(size))
        with LOCK:
            if 'keys' in body:
                STATE['keys'].update(body['keys'])
            for key in ('client', 'trafficStop'):
                if key in body:
                    STATE[key] = body[key]
            if 'arm' in body:
                if body['arm'] not in ('normal', 'slow', 'hold'):
                    self.send_error(400)
                    return
                STATE.update(arm=body['arm'], selected=None, held=False)
                RELEASE.clear()
            if body.get('release'):
                RELEASE.set()
        self.send_response(204)
        self.end_headers()


class Merchant(Control):
    def do_GET(self):
        self.send_response(204 if self.path == '/health' else 404)
        self.end_headers()

    def do_POST(self):
        try:
            size = int(self.headers['Content-Length'])
            if not 0 < size <= 65536:
                raise ValueError('bounded fixture body required')
            raw = self.rfile.read(size)
            body = json.loads(raw)
            timestamp, signature = self.headers['FlowPay-Signature'].split(',v1=')
            timestamp = timestamp.removeprefix('t=')
            identity = body['id']
            digest = hashlib.sha256(raw).digest()
            with LOCK:
                expected = hmac.new(STATE['keys'][self.path].encode(), timestamp.encode() + b'.' + raw, hashlib.sha256).hexdigest()
                previous = SEEN.get(identity)
                valid = hmac.compare_digest(signature, expected) and abs(int(timestamp) - time.time()) < 60
                valid &= self.headers['FlowPay-Event-Id'] == identity
                valid &= previous is None or previous['digest'] == digest
                valid &= not any(k.lower() in ('authorization', 'x-merchant-id', 'traceparent', 'baggage') for k in self.headers)
                if len(SEEN) >= 500 and previous is None:
                    valid = False
                STATE['invalid'] |= not valid
                if not valid:
                    self.send_response(400)
                    self.end_headers()
                    return
                row = SEEN.setdefault(identity, {'digest': digest, 'attempts': 0})
                row['attempts'] += 1
                mode = 'normal'
                if STATE['arm'] != 'normal' and STATE['selected'] is None:
                    STATE['selected'] = identity
                    mode = STATE['arm']
                    STATE['held'] = mode == 'hold'
            if mode == 'hold':
                RELEASE.wait(90)  # Bounded merchant fixture, not an app timeout override.
                with LOCK:
                    STATE['held'] = False
                    STATE['released'] += 1
            elif mode == 'slow':
                time.sleep(6)  # Deliberately exceeds the unchanged 5s client deadline.
            self.send_response(204)
            self.end_headers()
        except OSError:
            pass  # A timed-out/killed client may cancel a remotely accepted request.
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
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain('/run/secrets/cert.pem', '/run/secrets/key.pem')
    merchant.socket = tls.wrap_socket(merchant.socket, server_side=True)
    merchant.serve_forever()
