#!/bin/sh
# Generates synthetic disposable-lab secrets ONLY, in a fresh external directory.
set -eu
dest=${1:?Supply a fresh external directory}
test -d "$dest"
for file in DB_PASSWORD RABBITMQ_PASSWORD WEBHOOK_SECRET_ENCRYPTION_KEY GRAFANA_ADMIN_PASSWORD jwt-private.pem jwt-public.pem; do
  if test -e "$dest/$file"; then
    echo 'Refusing to overwrite existing lab secrets' >&2
    exit 1
  fi
done
umask 077
chmod 700 "$dest"
openssl rand -hex -out "$dest/DB_PASSWORD" 24
openssl rand -hex -out "$dest/RABBITMQ_PASSWORD" 24
openssl rand -hex -out "$dest/GRAFANA_ADMIN_PASSWORD" 24
openssl rand -base64 -out "$dest/WEBHOOK_SECRET_ENCRYPTION_KEY" 32
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$dest/jwt-private.pem" 2>/dev/null
openssl pkey -in "$dest/jwt-private.pem" -pubout -out "$dest/jwt-public.pem" 2>/dev/null
# Compose bind-backed secrets must be readable by non-root UIDs. The containing
# host directory remains 0700; expose individual files ONLY to intended services.
chmod 444 "$dest"/*
echo 'Synthetic lab secret files initialized (values withheld)'
