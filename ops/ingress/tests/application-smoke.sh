#!/bin/sh
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/../../.." && pwd)
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -am -- "$1"; else printf '%s' "$1"; fi; }
: "${FLOWPAY_JAR:?Set final verified executable JAR absolute path}"
test -f "$FLOWPAY_JAR"
project="flowpay-ingress-app-$(date +%s)-$$"
test -z "$(docker ps -aq --filter "label=com.docker.compose.project=$project")"
test -z "$(docker network ls -q --filter "label=com.docker.compose.project=$project")"
test -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$project")"
LAB_SECRETS_DIR=$(host_path "$(mktemp -d "${TMPDIR:-/tmp}/flowpay-ingress.XXXXXX")")
export LAB_SECRETS_DIR
compose() { docker compose --env-file "$(host_path /dev/null)" -p "$project" -f "$(host_path "$root/ops/ingress/tests/application-compose.yaml")" "$@"; }
cleanup() {
  result=$?
  if test "$result" -ne 0; then compose ps --all; fi
  case "$project" in flowpay-ingress-app-*) compose --profile checks down --volumes --remove-orphans >/dev/null ;; *) exit 1 ;; esac
  echo "Removed only owned real-app ingress containers/networks/data volumes; external synthetic secrets: $LAB_SECRETS_DIR"
}
trap cleanup EXIT HUP INT TERM
docker run --rm --network none --mount "type=bind,source=$LAB_SECRETS_DIR,target=/secrets" \
  --mount "type=bind,source=$(host_path "$root/ops/monitoring/tests"),target=/checks,readonly" \
  --entrypoint /bin/sh eclipse-temurin:21.0.10_7-jre-jammy@sha256:fa4854e6057665066cb79953616671c20f32b96a85d6dd8b78db0762203924c4 /checks/init-secrets.sh /secrets
compose config --quiet
compose --profile checks config --format json | compose run --rm --no-deps -T --entrypoint python probe -B /checks/validate.py
compose up -d
compose exec -T edge nginx -t -c /etc/flowpay/nginx.conf
compose run --rm --no-deps probe
# Wait for async consumer work; assertions are restricted to this invocation-owned database.
attempt=0
until test "$(compose exec -T postgres psql -X -U flowpay_lab -d flowpay_lab -tAc 'SELECT count(*) FROM ledger_transactions')" = 2; do
  attempt=$((attempt + 1)); test "$attempt" -lt 15; sleep 1
done
compose exec -T postgres psql -X -U flowpay_lab -d flowpay_lab -v ON_ERROR_STOP=1 -f - <<'SQL'
DO $$
BEGIN
  IF (SELECT count(*) FROM payment_intents) <> 1
    OR (SELECT count(*) FROM payment_intents WHERE status = 'PARTIALLY_REFUNDED' AND refunded_amount_minor = 1000 AND refund_reserved_minor = 0) <> 1
    OR (SELECT count(*) FROM payment_transactions) <> 1
    OR (SELECT count(*) FROM refunds WHERE status = 'SUCCEEDED') <> 1
    OR (SELECT count(*) FROM ledger_transactions) <> 2
    OR (SELECT count(*) FROM ledger_entries) <> 4
    OR EXISTS (SELECT ledger_transaction_id FROM ledger_entries GROUP BY ledger_transaction_id
       HAVING sum(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0)
  THEN RAISE EXCEPTION 'Owned ingress financial invariant failed'; END IF;
END $$;
SQL
echo 'PASS: real prod/DB/broker ingress auth, rate denial, replay and balanced unique financial outcomes'
