"""Owned TLS merchant. Synthetic keys/state in RAM only; never log request data."""
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import ssl
import threading
import time

STATE = {"secret": None, "active": False, "invalid": False, "events": {}, "hold": True}
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
        data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        with LOCK:
            STATE.update(data)
        self.send_response(204)
        self.end_headers()


class Merchant(Control):
    def do_POST(self):
        try:
            raw = self.rfile.read(int(self.headers["Content-Length"]))
            body = json.loads(raw)
            timestamp, signature = self.headers["FlowPay-Signature"].split(",v1=")
            timestamp = timestamp.removeprefix("t=")
            with LOCK:
                expected = hmac.new(STATE["secret"].encode(), timestamp.encode() + b"." + raw, hashlib.sha256).hexdigest()
                valid = hmac.compare_digest(expected, signature) and abs(int(timestamp) - time.time()) < 60
                valid = valid and self.headers["FlowPay-Event-Id"] == body["id"]
                valid = valid and not any(key.lower() in ("authorization", "x-merchant-id") for key in self.headers)
                digest = hashlib.sha256(raw).hexdigest()
                previous = STATE["events"].get(body["id"])
                valid = valid and (previous is None or previous["digest"] == digest)
                STATE["invalid"] |= not valid
                STATE["events"][body["id"]] = {"digest": digest, "type": body["type"]}
                hold = STATE["hold"]
                STATE["hold"] = False
                STATE["active"] = True
            if hold:
                time.sleep(3)  # Within the unchanged 5s production request deadline.
            self.send_response(204 if valid else 400)
            self.end_headers()
        except (BrokenPipeError, ConnectionResetError):
            # SIGTERM can cancel an already validated in-flight connection.
            # This is not a bad HMAC; the durable delivery must recover later.
            pass
        except Exception:
            with LOCK:
                STATE["invalid"] = True
        finally:
            with LOCK:
                STATE["active"] = False


if __name__ == "__main__":
    control = ThreadingHTTPServer(("0.0.0.0", 8081), Control)
    threading.Thread(target=control.serve_forever, daemon=True).start()
    tls = ThreadingHTTPServer(("0.0.0.0", 8443), Merchant)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain("/cert/cert.pem", "/cert/key.pem")
    tls.socket = context.wrap_socket(tls.socket, server_side=True)
    tls.serve_forever()
