"""Actual prod app/DB/broker behind edge. No credential, body or exception dumps."""
import concurrent.futures
import http.client
import json
import secrets
import sys
import time


def request(path, method="GET", body=None, headers=None):
    connection = http.client.HTTPConnection("edge", 8080, timeout=15)
    try:
        connection.request(method, path, None if body is None else json.dumps(body).encode(),
                           {"Content-Type": "application/json", **(headers or {})})
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()
    finally:
        connection.close()


def require(condition, message):
    if not condition:
        raise AssertionError(message)
    print("PASS: " + message)


def call(path, method="GET", body=None, headers=None, status=200):
    actual, _, raw = request("/api/v1" + path, method, body, headers)
    if actual != status:
        stage = next((name for name in ("register", "login", "api-keys", "confirm", "refunds")
                      if path.endswith("/" + name)), "payment")
        raise AssertionError("owned " + stage + " status failed (expected " + str(status) + ", got " + str(actual) + ")")
    return json.loads(raw) if raw else None


def main():
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            if request("/readyz")[0] == 200:
                break
        except OSError:
            pass
        time.sleep(1)
    else:
        raise AssertionError("prod startup behind edge failed")
    require(True, "real prod readiness behind enforcing edge")
    require(request("/api/v1/auth/refresh", "POST")[0] == 403, "real app refuses missing auth marker")
    preflight = request("/api/v1/auth/login", "OPTIONS", headers={"Origin": "https://untrusted.flowpay.test",
                        "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "X-FlowPay-CSRF"})
    require(preflight[0] == 403 and "Access-Control-Allow-Origin" not in preflight[1], "real auth CORS remains closed")
    suffix = secrets.token_hex(8)
    email, password = "edge-" + suffix + "@example.invalid", secrets.token_urlsafe(24)
    marker = {"X-FlowPay-CSRF": "1"}
    call("/auth/register", "POST", {"email": email, "password": password, "firstName": "Edge",
         "lastName": "Fixture", "merchantName": "Owned ingress fixture"}, marker, 201)
    token = call("/auth/login", "POST", {"email": email, "password": password}, marker)["data"]["accessToken"]
    key = call("/merchant/api-keys", "POST", {"name": "Fixture"}, {"Authorization": "Bearer " + token}, 201)["data"]["key"]
    create_headers = {"Authorization": "Bearer " + key, "Idempotency-Key": "edge-create-" + suffix}
    body = {"amount": 100000, "currency": "VND"}
    payment = call("/payment-intents", "POST", body, create_headers, 201)["data"]
    require(call("/payment-intents", "POST", body, create_headers, 201)["data"] == payment,
            "legitimate create replay keeps exact logical result")
    require(call("/payment-intents", "POST", {**body, "amount": 100001}, create_headers, 409)["code"] == "IDEMPOTENCY_KEY_REUSED",
            "legitimate conflict semantics unchanged")
    confirm_headers = {"Authorization": "Bearer " + key, "Idempotency-Key": "edge-confirm-" + suffix}
    path = "/payment-intents/" + payment["id"] + "/confirm"
    confirmed = call(path, "POST", headers=confirm_headers)["data"]
    require(confirmed["paymentStatus"] == "SUCCEEDED" and call(path, "POST", headers=confirm_headers)["data"] == confirmed,
            "successful confirm and replay unchanged")
    refund_headers = {"Authorization": "Bearer " + key, "Idempotency-Key": "edge-refund-" + suffix}
    refund_path = "/payment-intents/" + payment["id"] + "/refunds"
    refund = call(refund_path, "POST", {"amount": 1000}, refund_headers, 201)["data"]
    require(refund["status"] == "SUCCEEDED" and call(refund_path, "POST", {"amount": 1000}, refund_headers, 201)["data"] == refund,
            "successful refund and replay unchanged")
    # All accepted requests are replays. Denied requests never reach business state/provider.
    with concurrent.futures.ThreadPoolExecutor(max_workers=40) as executor:
        results = list(executor.map(lambda _: request("/api/v1" + path, "POST", headers={**confirm_headers,
                       "X-Forwarded-For": secrets.token_hex(8), "Forwarded": "for=127.0.0.1"}), range(40)))
    require(any(status == 429 for status, _, _ in results), "real financial replay flood is limited despite spoofed headers")
    for status, headers, raw in results:
        if status == 429:
            problem = json.loads(raw)
            assert problem["code"] == "RATE_LIMIT_EXCEEDED" and problem["requestId"] == headers["X-Request-Id"]
            assert headers["Retry-After"] == "1"
        else:
            assert status == 200 and json.loads(raw)["data"] == confirmed
    require(True, "accepted replays and edge-denied responses retain approved contracts")
    time.sleep(3)
    require(call(path, "POST", headers=confirm_headers)["data"] == confirmed, "same-key replay remains safe after rate refill")
    require(request("/actuator/prometheus", headers={"Forwarded": "host=app:9091", "X-Forwarded-Port": "9091"})[0] == 404,
            "real edge management denied under header spoofing")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        # Only fixed fixture labels/numeric statuses, never server body or third-party exception text.
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("FAIL: owned real-app ingress assertion failed; raw credential/body diagnostics withheld", file=sys.stderr)
        sys.exit(1)
