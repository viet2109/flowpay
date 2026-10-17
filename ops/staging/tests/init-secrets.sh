#!/bin/sh
# Synthetic secrets for an owned disposable smoke project, never operator keys.
set -eu
test "${FLOWPAY_STAGE_DISPOSABLE:-}" = synthetic-only
dest=/secrets
test -d "$dest"
for file in "$dest"/* "$dest"/.[!.]*; do
  if test -e "$file"; then
    echo 'Refusing to replace existing staging fixture secrets' >&2
    exit 1
  fi
done
sh /helpers/base-init.sh "$dest"
umask 077
openssl rand -hex -out "$dest/RABBITMQ_ADMIN_PASSWORD" 24
openssl rand -hex -out "$dest/LEGACY_WEBHOOK_SECRET" 32
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=edge \
  -addext 'subjectAltName=DNS:edge,DNS:localhost,IP:127.0.0.1,IP:::1' \
  -keyout "$dest/edge-key.pem" -out "$dest/edge-cert.pem" 2>/dev/null
sh /helpers/merchant-tls.sh
chmod 444 "$dest"/*
echo 'PASS: external synthetic staging secrets initialized; values withheld'
