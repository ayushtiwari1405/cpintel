#!/bin/sh
# Writes Alertmanager's configuration from the environment, then starts it.
#
# Alerts go by email through the same SMTP server password resets use (SMTP_* in .env), to
# CPINTEL_ALERT_EMAIL. Alertmanager reads no environment variables in its own file, so the file
# is written here, at start. Without SMTP or an address it still runs and sends nothing; the
# alerts stay visible on Prometheus's alerts page, as before.
set -e

# One YAML single-quoted scalar: a password may hold any character, and '' is how YAML writes '.
q() { printf "'%s'" "$(printf %s "$1" | sed "s/'/''/g")"; }

cfg=/tmp/alertmanager.yml

if [ -n "$SMTP_HOST" ] && [ -n "$CPINTEL_ALERT_EMAIL" ]; then
  {
    echo "global:"
    echo "  smtp_smarthost: $(q "$SMTP_HOST:${SMTP_PORT:-587}")"
    echo "  smtp_from: $(q "${CPINTEL_MAIL_FROM:-CPIntel <no-reply@cpintel.local>}")"
    if [ "${SMTP_STARTTLS:-true}" = "true" ]; then
      echo "  smtp_require_tls: true"
    else
      echo "  smtp_require_tls: false"
    fi
    if [ "${SMTP_AUTH:-true}" = "true" ] && [ -n "$SMTP_USER" ]; then
      echo "  smtp_auth_username: $(q "$SMTP_USER")"
      echo "  smtp_auth_password: $(q "$SMTP_PASSWORD")"
    fi
    echo "route:"
    echo "  receiver: email"
    echo "  group_by: [alertname]"
    # Soon enough to matter during a paper, rare enough not to bury the inbox.
    echo "  group_wait: 30s"
    echo "  group_interval: 5m"
    echo "  repeat_interval: 1h"
    echo "receivers:"
    echo "  - name: email"
    echo "    email_configs:"
    echo "      - to: $(q "$CPINTEL_ALERT_EMAIL")"
    echo "        send_resolved: true"
  } > "$cfg"
  echo "alertmanager: alerts are emailed to $CPINTEL_ALERT_EMAIL through $SMTP_HOST"
else
  cat > "$cfg" <<'YAML'
route:
  receiver: nobody
receivers:
  - name: nobody
YAML
  echo "alertmanager: SMTP_HOST or CPINTEL_ALERT_EMAIL is not set, so alerts are not sent anywhere"
fi

exec /bin/alertmanager --config.file="$cfg" --storage.path=/alertmanager
