"""Owned dual-stack TLS merchant; credentials and exact-body digests stay in RAM."""
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import ssl
import threading
import time

STATE = {'keys': {'/legacy': 'whsec_' + Path('/run/secrets/LEGACY_WEBHOOK_SECRET').read_text().strip()},
         'receipts': {}, 'invalid': False, 'requests': 0}
LOCK = threading.Lock()


class Control(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        with LOCK:
            body = json.dumps(STATE).encode()
        self.send_response(200)
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        with LOCK:
            if 'keys' in data:
                STATE['keys'].update(data.pop('keys'))
            STATE.update(data)
        self.send_response(204)
        self.end_headers()


class Merchant(Control):
    def do_GET(self):
        self.send_response(204 if self.path == '/health' else 404)
        self.end_headers()

    def do_POST(self):
        try:
            raw = self.rfile.read(int(self.headers['Content-Length']))
            body = json.loads(raw)
            timestamp, signature = self.headers['FlowPay-Signature'].split(',v1=')
            timestamp = timestamp.removeprefix('t=')
            with LOCK:
                expected = hmac.new(STATE['keys'][self.path].encode(), timestamp.encode() + b'.' + raw, hashlib.sha256).hexdigest()
                valid = hmac.compare_digest(signature, expected) and abs(int(timestamp) - time.time()) < 60
                valid = valid and self.headers['FlowPay-Event-Id'] == body['id']
                valid = valid and not any(key.lower() in ('authorization', 'x-merchant-id', 'traceparent', 'baggage') for key in self.headers)
                identity = self.path + ':' + body['id']
                digest = hashlib.sha256(raw).hexdigest()
                previous = STATE['receipts'].get(identity)
                valid = valid and (previous is None or previous['digest'] == digest)
                STATE['invalid'] |= not valid
                STATE['receipts'][identity] = {'digest': digest, 'type': body['type'], 'path': self.path}
                STATE['requests'] += 1
            self.send_response(204 if valid else 400)
            self.end_headers()
        except OSError:
            pass  # A stopped client can cancel a validated attempt; delivery remains recoverable.
        except Exception:
            with LOCK:
                STATE['invalid'] = True


class DualStackServer(ThreadingHTTPServer):
    address_family = socket.AF_INET6


if __name__ == '__main__':
    control = ThreadingHTTPServer(('0.0.0.0', 8081), Control)
    threading.Thread(target=control.serve_forever, daemon=True).start()
    tls = DualStackServer(('::', 8443), Merchant)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain('/run/secrets/cert.pem', '/run/secrets/key.pem')
    tls.socket = context.wrap_socket(tls.socket, server_side=True)
    tls.serve_forever()
