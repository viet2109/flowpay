#!/bin/sh
# Linux host entry point. Only creates/deletes this invocation's disposable project.
set -eu
command -v curl >/dev/null 2>&1
root=$(CDPATH='' cd -- "$(dirname -- "$0")/../../.." && pwd)
# Native Windows Docker under Git Bash needs explicit host-path normalization.
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -am -- "$1"; else printf '%s' "$1"; fi; }
: "${FLOWPAY_JAR:?Set absolute path to a verified executable JAR}"
test -f "$FLOWPAY_JAR"
project="flowpay-monitoring-$(date +%s)-$$"
existing=$(docker ps -aq --filter "label=com.docker.compose.project=$project")
test -z "$existing"
LAB_SECRETS_DIR=$(host_path "$(mktemp -d "${TMPDIR:-/tmp}/flowpay-monitoring.XXXXXX")")
export LAB_SECRETS_DIR
compose() { docker compose --env-file "$(host_path /dev/null)" -p "$project" -f "$(host_path "$root/ops/monitoring/compose.yaml")" "$@"; }
cleanup() {
  result=$?
  if test "$result" -ne 0; then
    compose ps --all
    # Print only reviewed startup/configuration failure markers; never arbitrary logs.
    compose logs --no-color app collector tempo grafana 2>/dev/null | grep -E 'Started FlowPayApplication|APPLICATION FAILED TO START|Permission denied|permission denied|Invalid production configuration|Unrecognized field|failed to create module|failed parsing config|error decoding' || true
  fi
  # Explicit prefix + ownership check; never target the dev/shared Compose project.
  case "$project" in flowpay-monitoring-*) compose down --volumes --remove-orphans >/dev/null ;; *) exit 1 ;; esac
  # Keep external secrets directory for manual deletion/inspection; no recursive shell removal.
  echo "Disposable project removed; delete external synthetic secrets directory: $LAB_SECRETS_DIR"
}
trap cleanup EXIT HUP INT TERM
docker run --rm --mount "type=bind,source=$LAB_SECRETS_DIR,target=/secrets" \
  --mount "type=bind,source=$(host_path "$root/ops/monitoring/tests"),target=/checks,readonly" \
  --entrypoint /bin/sh eclipse-temurin:21.0.10_7-jre-jammy@sha256:fa4854e6057665066cb79953616671c20f32b96a85d6dd8b78db0762203924c4 /checks/init-secrets.sh /secrets
compose config --quiet
compose --profile checks config --format json | compose run --rm --no-deps -T --entrypoint python probe -B /checks/validate.py
compose run --rm --no-deps --entrypoint /bin/promtool prometheus check config /etc/prometheus/prometheus.yaml
compose run --rm --no-deps --entrypoint /bin/promtool --workdir /etc/prometheus prometheus test rules rules.test.yaml
compose run --rm --no-deps collector validate --config=/etc/otel/config.yaml
compose up -d
compose run --rm --no-deps probe live
curl --silent --show-error --fail "http://127.0.0.1:${GRAFANA_LAB_PORT:-3300}/api/health" >/dev/null
compose stop collector prometheus grafana tempo
compose run --rm --no-deps probe outage
compose start collector prometheus grafana tempo
compose run --rm --no-deps probe recovered
curl --silent --show-error --fail "http://127.0.0.1:${GRAFANA_LAB_PORT:-3300}/api/health" >/dev/null
# Read-only financial proof across all three fixtures after consumers recover.
compose exec -T postgres psql -X -U flowpay_lab -d flowpay_lab -v ON_ERROR_STOP=1 -f - <<'SQL'
DO $$
BEGIN
  IF (SELECT count(*) FROM payment_intents WHERE status = 'PARTIALLY_REFUNDED' AND refunded_amount_minor = 1000 AND refund_reserved_minor = 0) <> 3
     OR (SELECT count(*) FROM refunds WHERE status = 'SUCCEEDED') <> 3
     OR (SELECT count(*) FROM ledger_transactions) <> 6
     OR (SELECT count(*) FROM ledger_entries) <> 12
     OR EXISTS (SELECT ledger_transaction_id FROM ledger_entries GROUP BY ledger_transaction_id
                HAVING sum(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0)
  THEN RAISE EXCEPTION 'Owned lab financial invariant failed'; END IF;
END $$;
SQL
echo 'PASS: complete isolated monitoring acceptance and balanced duplicate-safe ledger'
