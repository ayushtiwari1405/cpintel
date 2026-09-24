#!/bin/sh
# Runs once before nginx starts (the official image runs everything in docker-entrypoint.d).
#
#   - picks the certificate to serve (cpintel-cert — see it for the three sources);
#   - writes the lab networks from CPINTEL_LAB_NETWORKS for the rate limits;
#   - starts a watcher that re-checks the certificate every minute and reloads nginx when it
#     changes, so a first certificate from certbot, a renewal, or a campus certificate dropped
#     into ./nginx/certs takes effect without anybody restarting anything.
set -eu

cpintel-cert || true

# Lab networks behind one NAT address, comma or space separated, e.g. "10.20.0.0/16".
: > /etc/nginx/lab-networks.inc
for cidr in $(echo "${CPINTEL_LAB_NETWORKS:-}" | tr ',' ' '); do
  echo "$cidr 1;" >> /etc/nginx/lab-networks.inc
  echo "cpintel-tls: $cidr treated as a shared lab network"
done

# Detached so it outlives this script; its output goes to the container log.
nohup sh -c 'while sleep 60; do if cpintel-cert; then nginx -s reload; fi; done' \
  >/proc/1/fd/1 2>&1 &
