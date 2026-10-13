"""Real TCP-peer identity and concurrent edge limits; print only fixed assertions."""
import concurrent.futures
import http.client
import json
import re
import socket
import sys
import time


def require(condition, label):
    if not condition:
        raise AssertionError(label)
    print("PASS: " + label)


def request(path, method="GET", ipv6=False, spoof=False):
    headers = {"X-FlowPay-CSRF": "1", "X-Request-Id": "caller-controlled-id"}
    if spoof:
        headers.update({"Forwarded": "for=198.18.0.1;host=management;proto=https",
                        "X-Forwarded-For": str(time.monotonic_ns()), "X-Real-IP": "127.0.0.1",
                        "X-Forwarded-Host": "management", "X-Forwarded-Port": "9091",
                        "X-Forwarded-Proto": "https", "X-Forwarded-Prefix": "/actuator",
                        "X-Original-URL": "/actuator", "X-Rewrite-URL": "/actuator"})
    connection = http.client.HTTPConnection("fd00:232::3" if ipv6 else "172.30.232.3", 8080, timeout=5)
    try:
        connection.request(method, path, body=b"{}" if method != "GET" else None, headers=headers)
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()
    finally:
        connection.close()


def counters():
    return json.loads(request("/api/v1/_fixture/counters")[2])


def burst(path, size, ipv6=False, mixed_methods=False):
    with concurrent.futures.ThreadPoolExecutor(max_workers=size) as pool:
        return list(pool.map(lambda index: request(path,
            ("POST", "PUT", "PATCH", "DELETE")[index % 4] if mixed_methods else "POST", ipv6, True), range(size)))


def validate_denials(results, retry, scope):
    denied = [result for result in results if result[0] == 429]
    require(bool(denied), "excess requests rejected before upstream")
    for _, headers, raw in denied:
        body = json.loads(raw)
        assert headers["Content-Type"] == "application/problem+json"
        assert headers["Retry-After"] == retry
        assert re.fullmatch(r"[0-9a-f]{32}", headers["X-Request-Id"])
        assert body["requestId"] == headers["X-Request-Id"] and body["code"] == "RATE_LIMIT_EXCEEDED"
        assert body["status"] == 429 and body["instance"] == scope
        assert "caller-controlled" not in raw.decode() and "?" not in body["instance"]
    require(True, "safe correlated Problem Details and Retry-After")


def main():
    baseline = counters()
    auth = burst("/api/v1/auth/login?fixture-sensitive=do-not-reflect", 18)
    validate_denials(auth, "60", "/api/v1/auth")
    accepted = sum(result[0] == 200 for result in auth)
    require(accepted <= 6, "auth 10/minute plus burst-five budget")
    require(counters().get("/api/v1/auth/login", 0) - baseline.get("/api/v1/auth/login", 0) == accepted,
            "auth denial has zero backend side effects")
    require(request("/api/v1/auth/logout", "POST", spoof=True)[0] == 429,
            "same-IP auth routes share budget despite spoofed forwarded identity")
    require(request("/api/v1/%61uth/lo%67in", "POST", spoof=True)[0] == 429,
            "encoded auth route cannot bypass auth budget")
    v6 = burst("/api/v1/auth/refresh", 18, ipv6=True)
    validate_denials(v6, "60", "/api/v1/auth")
    require(0 < sum(result[0] == 200 for result in v6) <= 6,
            "independent native IPv6 TCP peer and bounded auth budget")
    detail = json.loads(request("/api/v1/_fixture/headers", spoof=True)[2])
    require(all(detail.values()), "forwarded/rewrite headers stripped and request ID/Host overwritten")
    require(request("/api/v1/_fixture/upstream-429")[1].get("Retry-After") == "7",
            "upstream 429 is not rewritten as edge rejection")
    mutations = burst("/api/v1/payment-intents", 80, mixed_methods=True)
    validate_denials(mutations, "1", "/api/v1")
    require(counters().get("/api/v1/payment-intents", 0) == sum(result[0] == 200 for result in mutations),
            "mutation denials have zero backend calls")
    # Methods share one mutation bucket; reads are not consumed by this policy.
    for index, method in enumerate(("POST", "PUT", "PATCH", "DELETE")):
        require(any(result[0] == 429 for result in mutations[index::4]),
                "shared mutation budget rejects " + method + " excess")
    require(request("/api/v1/payment-intents")[0] == 200, "read request unaffected by mutation exhaustion")
    time.sleep(3)  # Bounded fixture wait, not an application retry.
    require(request("/api/v1/refunds", "POST")[0] == 200, "mutation bucket recovers after refill")
    time.sleep(4)
    require(request("/api/v1/auth/login", "POST")[0] == 200, "auth bucket recovers after refill")
    require(request("/actuator/prometheus")[0] == 404, "edge does not expose management")
    try:
        socket.getaddrinfo("app", 8080)
        raise AssertionError("client resolves private backend bypass")
    except socket.gaierror:
        require(True, "private backend has no client-network DNS path")
    for address in ("172.30.233.9", "fd00:233::9"):
        try:
            with socket.create_connection((address, 8080), timeout=1):
                raise AssertionError("direct backend bypass reachable")
        except (socket.timeout, OSError):
            require(True, "direct backend IPv4/IPv6 network bypass denied")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("FAIL: owned ingress policy assertion failed; no raw response/credential diagnostics", file=sys.stderr)
        sys.exit(1)
