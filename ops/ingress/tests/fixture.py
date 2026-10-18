"""Owned header/counter backend. No credential/body/header logging or external calls."""
import collections
import http.server
import json
import re
import threading

counts = collections.Counter()
lock = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    def dispatch(self):
        path = self.path.split("?", 1)[0]
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        with lock:
            if path == "/api/v1/_fixture/counters":
                body = dict(counts)
            else:
                counts[path] += 1
                body = {"forwarded_safe": not any(self.headers.get(key) for key in (
                    "Forwarded", "X-Forwarded-For", "X-Real-IP", "X-Forwarded-Host",
                    "X-Forwarded-Port", "X-Forwarded-Proto", "X-Forwarded-Prefix",
                    "X-Original-URL", "X-Rewrite-URL")),
                    "request_id_safe": bool(re.fullmatch(r"[0-9a-f]{32}", self.headers.get("X-Request-Id", ""))),
                    "host_safe": self.headers.get("Host") == "flowpay.internal"}
        status = 429 if path == "/api/v1/_fixture/upstream-429" else 200
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        if status == 429:
            self.send_header("Retry-After", "7")
        self.end_headers()
        self.wfile.write(payload)

    do_POST = do_PUT = do_PATCH = do_DELETE = do_GET = dispatch

    def log_message(self, *_):
        pass


http.server.ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
