"""Read-only Compose/policy assertions; never print full configuration or credentials."""
import json
import pathlib
import re
import sys

config = json.load(sys.stdin)
services = config["services"]
for name, service in services.items():
    assert re.fullmatch(r"[^@]+:[^@:]+@sha256:[0-9a-f]{64}", service["image"])
    assert not service.get("ports") and "container_name" not in service
    assert int(service.get("mem_limit", 0)) > 0 and float(service.get("cpus", 0)) > 0
assert services["edge"]["user"] == "101:101" and services["edge"]["read_only"]
assert services["edge"]["cap_drop"] == ["ALL"]
assert services["edge"]["security_opt"] == ["no-new-privileges:true"]
assert set(services["probe"]["networks"]) == {"front"}
assert "front" not in services["app"]["networks"]
assert set(services["edge"]["networks"]) == ({"front", "lab"} if "postgres" in services else {"front", "backend"})
assert config["networks"]["front"]["internal"]
assert config["networks"]["lab" if "postgres" in services else "backend"]["internal"]
for mount in services["edge"]["volumes"]:
    assert mount["read_only"]
policy = pathlib.Path("/policy/nginx.conf").read_text()
proxy = pathlib.Path("/policy/proxy.conf").read_text()
assert "zone=flowpay_auth:10m rate=10r/m" in policy and "zone=flowpay_mutations:10m rate=10r/s" in policy
assert "burst=5 nodelay" in policy and "burst=20 nodelay" in policy
assert "access_log off" in policy and "error_log /dev/null" in policy
assert "proxy_next_upstream off" in proxy and "proxy_intercept_errors off" in proxy
assert not any(unsafe in policy + proxy for unsafe in ("set_real_ip_from", "real_ip_header", "$proxy_add_x_forwarded_for", "Access-Control-Allow-Origin"))
assert 'proxy_set_header X-Request-Id $request_id' in proxy
print("PASS: pinned bounded private topology, fixed TCP identity, no retry/CORS/logging and approved budgets")
