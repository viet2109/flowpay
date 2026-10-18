#!/bin/sh
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/../../.." && pwd)
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -am -- "$1"; else printf '%s' "$1"; fi; }
: "${FLOWPAY_JAR:?Set absolute path to verified executable JAR}"
: "${FLOWPAY_TEST_CLASSES:?Set matching target/test-classes absolute path}"
test -f "$FLOWPAY_JAR"
test -d "$FLOWPAY_TEST_CLASSES"
project="flowpay-egress-$(date +%s)-$$"
existing=$(docker ps -aq --filter "label=com.docker.compose.project=$project")
test -z "$existing"
existing=$(docker network ls -q --filter "label=com.docker.compose.project=$project")
test -z "$existing"
existing=$(docker volume ls -q --filter "label=com.docker.compose.project=$project")
test -z "$existing"
compose() { docker compose --env-file "$(host_path /dev/null)" -p "$project" -f "$(host_path "$root/ops/egress/tests/compose.yaml")" "$@"; }
cleanup() {
  result=$?
  if test "$result" -ne 0; then compose ps --all; fi
  case "$project" in flowpay-egress-*) compose down --volumes --remove-orphans >/dev/null ;; *) exit 1 ;; esac
  echo 'Removed only owned egress fixture containers/networks/TLS volume; no dev/shared resources touched.'
}
trap cleanup EXIT HUP INT TERM
compose config --quiet
compose up -d --wait --wait-timeout 45 fixtures proxy
parse=$(compose exec -T proxy /usr/sbin/squid -k parse -f /etc/squid/fixture.conf 2>&1)
if printf '%s' "$parse" | grep -E 'WARNING|ERROR|FATAL' >/dev/null; then
  echo 'Proxy ACL parsing warning/error; refusing security acceptance.'
  exit 1
fi
if compose exec -T proxy /bin/sh /egress/start-proxy.sh /etc/squid/invalid.conf; then
  echo 'Unsafe overlapping ACL startup was accepted.'
  exit 1
fi
echo 'PASS: ignored/overlapping ACL warnings fail closed at proxy startup'
compose run --rm --no-deps probe
echo 'PASS: complete owned Linux dual-stack effective-destination acceptance'
