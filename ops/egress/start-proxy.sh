#!/bin/sh
set -eu
configuration=${1:?Pass the reviewed Squid configuration path}
# Squid can ignore overlapping ranges or IPv6 ACLs and still return parse success.
# Refuse to start the connecting process in that case; never publish raw parse output.
if ! report=$(/usr/sbin/squid -k parse -f "$configuration" 2>&1); then
  echo 'Webhook egress proxy configuration rejected.' >&2
  exit 1
fi
if printf '%s' "$report" | grep -E 'WARNING|ERROR|FATAL' >/dev/null; then
  echo 'Webhook egress proxy ACL warning: refusing unsafe startup.' >&2
  exit 1
fi
exec /usr/sbin/squid -N -f "$configuration"
