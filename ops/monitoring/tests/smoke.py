"""Owned simulator fixtures only. No third-party packages; never print credentials/bodies."""
import base64
import json
import math
import pathlib
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def http(url, method="GET", body=None, headers=None, expected=200):
    payload = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=payload, method=method,
                                     headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    require(status == expected, "Unexpected HTTP status on " + urllib.parse.urlsplit(url).netloc +
            " (expected " + str(expected) + ", got " + str(status) + ")")
    return json.loads(data) if data else None


def wait_for(check, message, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (AssertionError, OSError, ValueError):
            pass
        time.sleep(2)
    raise AssertionError(message)


def query(expression, grafana_headers=None):
    if grafana_headers is None:
        base = "http://prometheus:9090"
    else:
        base = "http://grafana:3000/api/datasources/proxy/uid/flowpay-prometheus"
    result = http(base + "/api/v1/query?" + urllib.parse.urlencode({"query": expression}),
                  headers=grafana_headers)
    require(result["status"] == "success", "PromQL failed")
    return result["data"]["result"]


def metric_at_least(expression, count):
    values = query(expression)
    return values and all(math.isfinite(float(item["value"][1])) for item in values) and sum(
        float(item["value"][1]) for item in values) >= count


def span_names(value):
    if isinstance(value, dict):
        # Tempo JSON uses both OTLP and its legacy 'batches' envelope across releases.
        if "spanId" in value and "name" in value:
            yield value["name"]
        for child in value.values():
            yield from span_names(child)
    elif isinstance(value, list):
        for child in value:
            yield from span_names(child)


def trace_visible(trace_id, forbidden):
    trace = http("http://tempo:3200/api/traces/" + trace_id,
                 headers={"Accept": "application/json"})
    names = set(span_names(trace))
    require({"http.server", "payment.provider", "outbox.publish", "ledger.consume",
             "webhook.materialize"} <= names, "End-to-end trace ancestry is not yet exported")
    serialized = json.dumps(trace)
    require(all(value not in serialized for value in forbidden), "Sensitive fixture reached trace storage")
    return True


def business_fixture():
    base = "http://app:8080/api/v1"
    suffix = secrets.token_hex(8)
    email, password = "lab-" + suffix + "@example.invalid", secrets.token_urlsafe(24)
    http(base + "/auth/register", "POST", {
        "email": email, "password": password, "firstName": "Lab", "lastName": "Fixture",
        "merchantName": "Disposable monitoring fixture"}, expected=201)
    jwt = http(base + "/auth/login", "POST", {"email": email, "password": password})["data"]["accessToken"]
    key = http(base + "/merchant/api-keys", "POST", {"name": "Monitoring fixture"},
               {"Authorization": "Bearer " + jwt}, expected=201)["data"]["key"]
    headers = {"Authorization": "Bearer " + key, "Idempotency-Key": "lab-create-" + suffix}
    body = {"amount": 100000, "currency": "VND"}
    payment = http(base + "/payment-intents", "POST", body, headers, 201)["data"]["id"]
    require(http(base + "/payment-intents", "POST", body, headers, 201)["data"]["id"] == payment,
            "Create replay changed logical result")
    conflict = http(base + "/payment-intents", "POST", {**body, "amount": 100001}, headers, 409)
    require(conflict["code"] == "IDEMPOTENCY_KEY_REUSED", "Conflict contract changed")
    trace_id = secrets.token_hex(16)
    confirm_headers = {"Authorization": "Bearer " + key, "Idempotency-Key": "lab-confirm-" + suffix,
                       "traceparent": "00-" + trace_id + "-" + secrets.token_hex(8) + "-01"}
    confirm = http(base + "/payment-intents/" + payment + "/confirm", "POST", headers=confirm_headers)["data"]
    require(confirm["paymentStatus"] == "SUCCEEDED", "Payment did not succeed")
    require(http(base + "/payment-intents/" + payment + "/confirm", "POST", headers=confirm_headers)["data"] == confirm,
            "Confirm replay changed logical result")
    refund_headers = {"Authorization": "Bearer " + key, "Idempotency-Key": "lab-refund-" + suffix}
    refund = http(base + "/payment-intents/" + payment + "/refunds", "POST",
                  {"amount": 1000, "reason": "Monitoring fixture"}, refund_headers, 201)["data"]
    require(refund["status"] == "SUCCEEDED", "Refund did not succeed")
    view = http(base + "/payment-intents/" + payment, headers={"Authorization": "Bearer " + key})["data"]
    require(view["status"] == "PARTIALLY_REFUNDED" and view["refundedAmount"] == 1000
            and view["refundableAmount"] == 99000, "Financial snapshot changed")
    http("http://app:8080/actuator/prometheus", expected=401)
    return trace_id, [email, password, jwt, key, "Disposable monitoring fixture", "Monitoring fixture"]


def monitoring_fixture(trace_id, forbidden):
    password = pathlib.Path("/run/secrets/GRAFANA_ADMIN_PASSWORD").read_text().strip()
    encoded = base64.b64encode(("lab-admin:" + password).encode()).decode()
    headers = {"Authorization": "Basic " + encoded}
    wait_for(lambda: http("http://grafana:3000/api/health")["database"] == "ok", "Grafana failed")
    wait_for(lambda: bool(http("http://grafana:3000/api/dashboards/uid/flowpay-operations", headers=headers)),
             "Grafana dashboard provisioning did not become ready")
    dashboard = http("http://grafana:3000/api/dashboards/uid/flowpay-operations", headers=headers)["dashboard"]
    provisioned = json.loads(pathlib.Path("/dashboards/operations.json").read_text())
    require(dashboard["panels"] == provisioned["panels"], "Dashboard provisioning differs from versioned JSON")
    for uid in ("flowpay-prometheus", "flowpay-tempo"):
        wait_for(lambda uid=uid: bool(http("http://grafana:3000/api/datasources/uid/" + uid, headers=headers)),
                 "Grafana datasource provisioning did not become ready")
        require(http("http://grafana:3000/api/datasources/uid/" + uid, headers=headers)["uid"] == uid,
                "Datasource not provisioned")
    print("PASS: Grafana dashboard and datasources provisioned", flush=True)
    wait_for(lambda: metric_at_least('up{job=~"flowpay|rabbitmq|rabbitmq-queues|collector|tempo|prometheus"}', 6),
             "Required scrape targets unavailable")
    for expression in (
        'flowpay_payment_confirmation_outcomes_total{outcome="SUCCESS"}',
        'flowpay_refund_outcomes_total{outcome="SUCCESS"}',
        'flowpay_idempotency_decisions_total{decision="REPLAY"}',
        'flowpay_idempotency_decisions_total{decision="KEY_REUSED"}',
        'flowpay_ledger_postings_total',
        'rabbitmq_detailed_queue_consumers{queue="flowpay.ledger.events"}',
        'rabbitmq_detailed_queue_consumers{queue="flowpay.webhook.events"}',
        'flowpay_metrics_sample_age_seconds{component="OUTBOX"} >= bool 0',
        'flowpay_metrics_sample_age_seconds{component="WEBHOOK"} >= bool 0',
    ):
        wait_for(lambda expression=expression: metric_at_least(expression, 1), "Expected metric not ingested: " + expression)
    for panel in provisioned["panels"]:
        for target in panel["targets"]:
            # Grafana health can precede datasource proxy/plugin readiness. Only retry
            # read-only checks, never fixture business writes; empty idle data is valid.
            wait_for(lambda target=target: query(target["expr"], headers) is not None,
                     "Dashboard query not ready: " + panel["title"])
    print("PASS: native ingestion and all dashboard PromQL targets", flush=True)
    rules = http("http://prometheus:9090/api/v1/rules")["data"]["groups"]
    require(len([rule for group in rules for rule in group["rules"] if rule["type"] == "alerting"]) == 12,
            "Operational alert provisioning incomplete")
    require(all(rule["health"] == "ok" for group in rules for rule in group["rules"]), "Rule evaluation failed")
    wait_for(lambda: trace_visible(trace_id, forbidden), "Controlled business trace not retrievable")
    # Prove Grafana's datasource proxy can retrieve the same stored trace, not merely a healthy backend.
    wait_for(lambda: bool(http("http://grafana:3000/api/datasources/proxy/uid/flowpay-tempo/api/traces/" + trace_id,
                              headers={**headers, "Accept": "application/json"})), "Grafana trace proxy not ready")


def main():
    mode = sys.argv[1] if len(sys.argv) == 2 else "live"
    require(mode in {"live", "outage", "recovered"}, "Unsupported smoke mode")
    wait_for(lambda: http("http://app:8080/readyz")["status"] == "UP", "Prod app never became ready")
    print("PASS: prod readiness", flush=True)
    trace_id, forbidden = business_fixture()
    print("PASS: business financial snapshot, replay and conflict", flush=True)
    if mode != "outage":
        monitoring_fixture(trace_id, forbidden)
    else:
        # The host orchestrator stops ALL four monitoring services before this call.
        for url in ("http://prometheus:9090/-/ready", "http://grafana:3000/api/health",
                    "http://tempo:3200/ready", "http://collector:4318/v1/traces"):
            try:
                urllib.request.urlopen(url, timeout=2)
            except urllib.error.HTTPError:
                # Any HTTP response, even 404/405, proves the service is still reachable.
                raise AssertionError("Outage fixture still has a reachable monitoring service")
            except (OSError, urllib.error.URLError):
                continue
            raise AssertionError("Outage fixture still has a running monitoring service")
    print("PASS: " + mode + " financial/replay/conflict checks" +
          ("; metrics, dashboard queries, alert provisioning and stored E2E trace" if mode != "outage" else "; monitoring unavailable"))


if __name__ == "__main__":
    try:
        main()
    except AssertionError as error:
        # Only our fixed assertion messages are printed, never third-party exception text.
        print("FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        # Never print server responses, URLs with credentials, request data or exception text.
        print("FAIL: monitoring smoke assertion failed; inspect only owned sanitized diagnostics", file=sys.stderr)
        sys.exit(1)
