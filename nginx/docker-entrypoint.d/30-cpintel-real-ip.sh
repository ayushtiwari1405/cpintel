#!/bin/sh
# Runs once before nginx starts. Writes /etc/nginx/real-ip.inc, which the site config includes.
#
# Behind a CDN or a load balancer every connection reaches nginx from the proxy's address, so
# the per-address rate limits, CPINTEL_LAB_NETWORKS and the audit log would all see the proxy
# instead of the person: a whole lab becomes one caller. CPINTEL_REAL_IP_FROM names the proxies
# that may say who the caller is (CIDRs, comma or space separated); for a connection from one of
# them nginx takes the caller's address from CPINTEL_REAL_IP_HEADER (default CF-Connecting-IP,
# which is Cloudflare's). A connection from anywhere else is never believed, so a client cannot
# claim an address by sending the header itself.
#
# Unset (the default) the file is empty and nothing changes.
set -eu

out="${CPINTEL_REAL_IP_FILE:-/etc/nginx/real-ip.inc}"
header="${CPINTEL_REAL_IP_HEADER:-CF-Connecting-IP}"
: > "$out"

from=$(echo "${CPINTEL_REAL_IP_FROM:-}" | tr ',' ' ')
[ -n "$(echo "$from" | tr -d ' ')" ] || exit 0

# Interpolated into nginx's config, so only what an address, a range or a header name can
# contain gets through. A typo stops the container rather than writing a broken directive.
case "$header" in
  ''|*[!A-Za-z0-9-]*) echo "cpintel-edge: CPINTEL_REAL_IP_HEADER '$header' is not a header name" >&2; exit 1 ;;
esac
for cidr in $from; do
  case "$cidr" in
    *[!0-9A-Fa-f:./]*) echo "cpintel-edge: CPINTEL_REAL_IP_FROM entry '$cidr' is not an address or CIDR" >&2; exit 1 ;;
  esac
  echo "set_real_ip_from $cidr;" >> "$out"
done
echo "real_ip_header $header;" >> "$out"
echo "cpintel-edge: caller address taken from $header for connections from: $from"
