"""Real image API/merchant probes. No response/credential/exception dumps."""
import http.client
import json
import secrets
import sys
import time


def request(host, port, path, method="GET", body=None, headers=None, status=200):
    connection = http.client.HTTPConnection(host, port, timeout=15)
    try:
        connection.request(method, path, None if body is None else json.dumps(body).encode(),
                           {"Content-Type": "application/json", **(headers or {})})
        response = connection.getresponse()
        raw = response.read()
        assert response.status == status, f"owned status expected {status}, got {response.status}"
        return json.loads(raw) if raw else None
    finally:
        connection.close()


def control(body=None):
    return request("receiver", 8081, "/", "GET" if body is None else "POST", body, status=200 if body is None else 204)


def api(path, method="GET", body=None, headers=None, status=200):
    return request("app", 8080, "/api/v1" + path, method, body, headers, status)


def wait(condition, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if condition():
                return
        except (OSError, AssertionError):
            pass
        time.sleep(0.2)
    raise AssertionError("owned readiness/delivery deadline exceeded")


def main(phase):
    wait(lambda: request("app", 8080, "/readyz")["status"] == "UP")
    if phase == "ready":
        return
    if phase == "prepare":
        suffix = secrets.token_hex(8)
        email, password = "image-" + suffix + "@example.invalid", secrets.token_urlsafe(24)
        marker = {"X-FlowPay-CSRF": "1"}
        api("/auth/register", "POST", {"email": email, "password": password, "firstName": "Image",
            "lastName": "Fixture", "merchantName": "Owned image fixture"}, marker, 201)
        jwt = api("/auth/login", "POST", {"email": email, "password": password}, marker)["data"]["accessToken"]
        dashboard = {"Authorization": "Bearer " + jwt}
        key = api("/merchant/api-keys", "POST", {"name": "Image fixture"}, dashboard, 201)["data"]["key"]
        endpoint = api("/merchant/webhook-endpoints", "POST", {"url": "https://11.203.0.10:8443/events",
                       "events": ["payment.succeeded", "refund.succeeded"]}, dashboard, 201)["data"]
        control({"secret": endpoint["secret"], "key": key, "jwt": jwt, "suffix": suffix, "endpoint": endpoint["id"]})
        headers = {"Authorization": "Bearer " + key, "Idempotency-Key": "image-create-" + suffix}
        payment = api("/payment-intents", "POST", {"amount": 100000, "currency": "VND"}, headers, 201)["data"]
        control({"payment": payment["id"]})
        confirm = {"Authorization": "Bearer " + key, "Idempotency-Key": "image-confirm-" + suffix}
        result = api("/payment-intents/" + payment["id"] + "/confirm", "POST", headers=confirm)["data"]
        assert result["paymentStatus"] == "SUCCEEDED"
        control({"confirmation": result})
        wait(lambda: control()["active"], 20)
        # Host sends SIGTERM immediately while merchant HTTP is in flight.
    elif phase == "refund":
        state = control()
        assert not state["invalid"], "owned merchant signature invariant failed"
        jwt = {"Authorization": "Bearer " + state["jwt"]}
        assert api("/merchant/webhook-endpoints/" + state["endpoint"], headers=jwt)["data"]["status"] == "ACTIVE"
        headers = {"Authorization": "Bearer " + state["key"], "Idempotency-Key": "image-refund-" + state["suffix"]}
        result = api("/payment-intents/" + state["payment"] + "/refunds", "POST", {"amount": 1000}, headers, 201)["data"]
        assert result["status"] == "SUCCEEDED"  # Broker is stopped by the host.
        control({"refund": result})
    elif phase == "verify":
        state = control()
        headers = {"Authorization": "Bearer " + state["key"], "Idempotency-Key": "image-confirm-" + state["suffix"]}
        assert api("/payment-intents/" + state["payment"] + "/confirm", "POST", headers=headers)["data"] == state["confirmation"]
        headers["Idempotency-Key"] = "image-refund-" + state["suffix"]
        assert api("/payment-intents/" + state["payment"] + "/refunds", "POST", {"amount": 1000}, headers, 201)["data"] == state["refund"]
        payment = api("/payment-intents/" + state["payment"], headers=headers)["data"]
        assert payment["status"] == "PARTIALLY_REFUNDED" and payment["refundedAmount"] == 1000
        wait(lambda: {item["type"] for item in control()["events"].values()} == {"payment.succeeded", "refund.succeeded"})
        assert not control()["invalid"]
    else:
        raise AssertionError("unknown probe phase")


if __name__ == "__main__":
    try:
        main(sys.argv[1])
        print("PASS: owned image API/merchant phase " + sys.argv[1])
    except AssertionError as error:
        message = str(error)
        if not message.startswith("owned "):
            message = "owned image assertion failed"
        print("FAIL: " + message)
        sys.exit(1)
    except Exception:
        print("FAIL: owned image API/merchant probe; raw credentials/body/diagnostics withheld")
        sys.exit(1)
