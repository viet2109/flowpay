#!/bin/sh
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/../../.." && pwd)
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -am -- "$1"; else printf '%s' "$1"; fi; }
project="flowpay-ingress-$(date +%s)-$$"
test -z "$(docker ps -aq --filter "label=com.docker.compose.project=$project")"
test -z "$(docker network ls -q --filter "label=com.docker.compose.project=$project")"
test -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$project")"
compose() { docker compose --env-file "$(host_path /dev/null)" -p "$project" -f "$(host_path "$root/ops/ingress/tests/compose.yaml")" "$@"; }
cleanup() {
  result=$?
  if test "$result" -ne 0; then compose ps --all; fi
  case "$project" in flowpay-ingress-*) compose down --volumes --remove-orphans >/dev/null ;; *) exit 1 ;; esac
  echo 'Removed only owned ingress fixture resources; no dev/shared data touched.'
}
trap cleanup EXIT HUP INT TERM
compose config --quiet
compose config --format json | compose run --rm --no-deps -T --entrypoint python probe -B /checks/validate.py
compose up -d --wait --wait-timeout 45 app edge
compose exec -T edge nginx -t -c /etc/flowpay/nginx.conf
compose run --rm --no-deps probe
echo 'PASS: owned ingress TCP identity, rate-limit and non-forwarding gate'
