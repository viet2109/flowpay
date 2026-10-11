"""Owned authoritative DNS, real TLS merchant and private sink; no external targets."""
import collections
import hashlib
import hmac
import http.server
import ipaddress
import socket
import socketserver
import ssl
import struct
import threading
import time

state = collections.Counter()
lock = threading.Lock()
public4, public6 = "11.203.0.10", "2606:4700:ffff::10"
private4, private6 = "172.30.231.9", "fd00:231::9"
raw_body = '{ "message": "fixture UTF-8 €" }\n'.encode()


class Dns(socketserver.BaseRequestHandler):
    def handle(self):
        packet, sock = self.request
        offset, labels = 12, []
        while packet[offset]:
            length = packet[offset]
            labels.append(packet[offset + 1:offset + 1 + length].decode("ascii"))
            offset += 1 + length
        offset += 1
        name = ".".join(labels)
        kind, klass = struct.unpack("!HH", packet[offset:offset + 4])
        question = packet[12:offset + 4]
        addresses = []
        with lock:
            rebound = state["rebind-a"] > 0
        if kind == 1:
            addresses = {
                "v6.test": [], "private.test": [private4], "private6.test": [],
                "mixed4.test": [public4, private4], "mixed6.test": [],
                "cross-family.test": [public4], "reverse-family.test": [private4],
                "metadata.test": ["169.254.169.254"], "reserved.test": ["198.18.0.1"],
                "rebind.test": [private4 if rebound else public4],
            }.get(name, [public4])
            if name == "delayed.test":
                with lock:
                    state["delayed-a"] += 1
            if name == "rebind.test":
                with lock:
                    state["rebind-a"] += 1
        elif kind == 28:
            addresses = {
                "v6.test": [public6], "private6.test": [private6],
                "mixed6.test": [public6, private6], "cross-family.test": [private6],
                "reverse-family.test": [public6], "delayed.test": [private6],
            }.get(name, [])
            if name == "delayed.test":
                with lock:
                    state["delayed-aaaa"] += 1
                time.sleep(1)
        answers = b""
        for address in addresses:
            packed = ipaddress.ip_address(address).packed
            answers += b"\xc0\x0c" + struct.pack("!HHIH", kind, klass, 0, len(packed)) + packed
        reply = packet[:2] + struct.pack("!HHHHH", 0x8180, 1, len(addresses), 0, 0) + question + answers
        sock.sendto(reply, self.client_address)


class Udp(socketserver.ThreadingUDPServer):
    daemon_threads = True


class Control(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with lock:
            body = str(state[self.path.removeprefix("/count/")]).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


class Merchant(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        signature = hmac.new(b"whsec_spike", b"1700000000." + body, hashlib.sha256).hexdigest()
        valid = (self.headers.get("FlowPay-Signature") == "t=1700000000,v1=" + signature
                 and body == raw_body and self.headers.get("FlowPay-Event-Id") == "evt_fixture"
                 and self.headers.get("User-Agent") == "FlowPay-Webhooks/1.0"
                 and self.headers.get("Content-Type") == "application/json"
                 and not any(self.headers.get(key) for key in ("traceparent", "baggage", "Authorization")))
        host = self.headers.get("Host", "").split(":")[0]
        with lock:
            state["invalid-hmac"] += int(not valid)
            state["private-requests"] += int(self.server.private_sink)
            state["unexpected-requests"] += int(host.endswith(".test") and host not in ("good.test", "v6.test", "rebind.test"))
        if self.path == "/stall":
            with lock:
                state["stalled"] += 1
            time.sleep(8)
            self.close_connection = True
            return
        self.send_response(302 if self.path == "/redirect" else 204 if valid else 400)
        if self.path == "/redirect":
            self.send_header("Location", "https://private.test:8443/redirected")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *_):
        pass


class V6(http.server.ThreadingHTTPServer):
    address_family = socket.AF_INET6


def tls_server(address, port, untrusted=False, private=False, dns_only=False):
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    certificate = "untrusted-cert.pem" if untrusted else "dns-only-cert.pem" if dns_only else "cert.pem"
    key = "untrusted-key.pem" if untrusted else "dns-only-key.pem" if dns_only else "key.pem"
    tls.load_cert_chain("/cert/" + certificate, "/cert/" + key)
    server = (V6 if ":" in address else http.server.ThreadingHTTPServer)((address, port), Merchant)
    server.private_sink = private
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()


for address in (public4, public6, private4, private6):
    tls_server(address, 8443, private=address in (private4, private6))
tls_server(public4, 8444, untrusted=True)
for address in (public4, public6):
    tls_server(address, 8445, dns_only=True)
dns = Udp((public4, 53), Dns)
threading.Thread(target=dns.serve_forever, daemon=True).start()
http.server.ThreadingHTTPServer(("0.0.0.0", 8080), Control).serve_forever()
