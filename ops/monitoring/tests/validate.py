"""Static contract check of Docker Compose's normalized JSON (read from stdin)."""
import json
import pathlib
import re
import sys

config = json.load(sys.stdin)
services = config["services"]
assert set(services) == {"app", "postgres", "rabbitmq", "prometheus", "grafana", "collector", "tempo", "probe"}
for name, service in services.items():
    assert re.fullmatch(r"[^@]+:[^@:]+@sha256:[0-9a-f]{64}", service["image"]), "Exact version and digest required"
    assert "container_name" not in service, "Fixed names break project isolation"
    assert int(service.get("mem_limit", 0)) > 0 and float(service.get("cpus", 0)) > 0, "Finite lab budgets required"
    assert set(service["networks"]) == ({"lab", "ui"} if name == "grafana" else {"lab"}), "Do not join development/shared networks"
    if name != "grafana":
        assert not service.get("ports"), "Private service must not publish ports"
    else:
        assert len(service["ports"]) == 1 and service["ports"][0]["host_ip"] == "127.0.0.1"
assert config["networks"]["lab"]["internal"] is True
assert services["app"]["environment"]["SPRING_PROFILES_ACTIVE"] == "prod"
assert services["app"]["environment"]["MANAGEMENT_SERVER_ADDRESS"] == "0.0.0.0"
assert services["app"]["environment"]["MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED"] == "false"
assert services["app"]["read_only"] and services["app"]["user"] != "0"
assert "GF_SECURITY_ADMIN_PASSWORD" not in services["grafana"]["environment"]
assert services["grafana"]["environment"]["GF_AUTH_ANONYMOUS_ENABLED"] == "false"
assert all("file" in secret for secret in config["secrets"].values())
dashboard = json.loads(pathlib.Path("/dashboards/operations.json").read_text())
assert dashboard["uid"] == "flowpay-operations" and len(dashboard["panels"]) == 21
for panel in dashboard["panels"]:
    assert panel["datasource"]["uid"] == "flowpay-prometheus"
    assert panel["targets"] and all(target["expr"] for target in panel["targets"])
    assert not re.search(r"merchant_id|event_id|trace_id|public_id|idempotency_key", json.dumps(panel))
rules = pathlib.Path("/rules/rules.yaml").read_text()
fixtures_text = pathlib.Path("/rules/rules.test.yaml").read_text()
fixtures = json.loads(fixtures_text[fixtures_text.index("{"):])
alert_names = set(re.findall(r"^\s+- alert: (\w+)$", rules, re.MULTILINE))
covered = {case["alertname"] for test in fixtures["tests"] for case in test.get("alert_rule_test", [])
           if case["exp_alerts"]}
assert len(alert_names) == 12 and covered == alert_names, "Every alert requires a firing fixture"
for alert in alert_names:
    assert any(case["alertname"] == alert and not case["exp_alerts"]
               for test in fixtures["tests"] for case in test.get("alert_rule_test", [])), "Resolution fixture missing"
runbook = pathlib.Path("/runbook.md").read_text()
anchors = {re.sub(r"[^a-z0-9 -]", "", heading.lower()).replace(" ", "-")
           for heading in re.findall(r"^### (.+)$", runbook, re.MULTILINE)}
assert set(re.findall(r"runbook: docs/monitoring-runbook.md#([a-z-]+)", rules)) <= anchors
print("PASS: pinned images, isolated/private topology, external credentials, finite budgets and dashboard contract")
