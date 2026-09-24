#!/bin/sh
# Chooses the certificate nginx serves. Exit 0 when it changed what is served, 1 when not.
#
# Three sources, in order of preference:
#   1. Let's Encrypt — /etc/letsencrypt/live/$CPINTEL_DOMAIN, written by the certbot service
#      (docker compose --profile letsencrypt) on a server the internet can reach.
#   2. A certificate you provide — /etc/nginx/certs/{fullchain,privkey}.pem, mounted from
#      ./nginx/certs. For a closed campus network, with a certificate from campus IT.
#   3. A temporary self-signed one, so nginx can start — and so certbot's first challenge,
#      which needs nginx up on :80, can be answered at all. Browsers will warn about it.
#
# The chosen files are copied to /etc/nginx/tls, which is all the site config knows about.
set -eu

DOMAIN="${CPINTEL_DOMAIN:-localhost}"
LIVE="/etc/letsencrypt/live/$DOMAIN"
PROVIDED="/etc/nginx/certs"
TLS="/etc/nginx/tls"
mkdir -p "$TLS"

src=""
kind=""
if [ -s "$LIVE/fullchain.pem" ] && [ -s "$LIVE/privkey.pem" ]; then
  src="$LIVE"; kind="Let's Encrypt"
elif [ -s "$PROVIDED/fullchain.pem" ] && [ -s "$PROVIDED/privkey.pem" ]; then
  src="$PROVIDED"; kind="provided"
fi

if [ -n "$src" ]; then
  if cmp -s "$src/fullchain.pem" "$TLS/fullchain.pem" 2>/dev/null \
     && cmp -s "$src/privkey.pem" "$TLS/privkey.pem" 2>/dev/null; then
    exit 1
  fi
  # Copied (following Let's Encrypt's symlinks) and swapped in whole, so nginx never reads a
  # half-written key.
  cp "$src/fullchain.pem" "$TLS/fullchain.pem.new"
  cp "$src/privkey.pem" "$TLS/privkey.pem.new"
  mv "$TLS/fullchain.pem.new" "$TLS/fullchain.pem"
  mv "$TLS/privkey.pem.new" "$TLS/privkey.pem"
  echo "cpintel-tls: serving the $kind certificate for $DOMAIN"
  exit 0
fi

if [ ! -s "$TLS/fullchain.pem" ]; then
  openssl req -x509 -nodes -newkey rsa:2048 -days 30 -subj "/CN=$DOMAIN" \
    -keyout "$TLS/privkey.pem" -out "$TLS/fullchain.pem" >/dev/null 2>&1
  echo "cpintel-tls: no certificate for $DOMAIN yet — serving a temporary self-signed one"
  exit 0
fi
exit 1
