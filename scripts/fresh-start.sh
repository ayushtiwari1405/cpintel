#!/bin/bash
echo "=== CPIntel FULL RESET (destroys all data) ==="
read -p "Are you sure? This deletes ALL data. [y/N] " confirm
[[ "$confirm" != "y" ]] && echo "Aborted." && exit 0

pkill -f 'spring-boot' 2>/dev/null || true
pkill -f 'vite'        2>/dev/null || true

docker compose down -v
docker compose up postgres mongodb redis -d

echo "Waiting for Postgres..."
while [ "$(docker inspect -f '{{.State.Health.Status}}' cpintel-postgres 2>/dev/null)" != "healthy" ]; do
  sleep 2
  echo -n "."
done
echo ""
echo "Databases ready."
