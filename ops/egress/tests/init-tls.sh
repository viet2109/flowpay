#!/bin/sh
set -eu
# The volume belongs to a fresh, invocation-owned project. Never rotate a running fixture CA.
test ! -e /cert/cert.pem
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=good.test \
  -addext 'subjectAltName=DNS:good.test,DNS:v6.test,DNS:rebind.test,DNS:private.test,DNS:private6.test,DNS:mixed4.test,DNS:mixed6.test,DNS:cross-family.test,DNS:reverse-family.test,DNS:delayed.test,IP:11.203.0.10,IP:2606:4700:ffff::10' \
  -keyout /cert/key.pem -out /cert/cert.pem 2>/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=good.test \
  -addext 'subjectAltName=DNS:good.test' -keyout /cert/untrusted-key.pem -out /cert/untrusted-cert.pem 2>/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=good.test \
  -addext 'subjectAltName=DNS:good.test' -keyout /cert/dns-only-key.pem -out /cert/dns-only-cert.pem 2>/dev/null
keytool -importcert -noprompt -alias owned-fixture -file /cert/cert.pem \
  -keystore /cert/trust.p12 -storetype PKCS12 -storepass fixture-only >/dev/null 2>&1
keytool -importcert -noprompt -alias owned-dns-only -file /cert/dns-only-cert.pem \
  -keystore /cert/trust.p12 -storetype PKCS12 -storepass fixture-only >/dev/null 2>&1
chmod 444 /cert/*.pem /cert/trust.p12
