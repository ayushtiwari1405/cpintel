#!/bin/bash
echo "=== CPIntel FULL RESET (destroys all data) ==="
read -p "Are you sure? This deletes ALL data. [y/N] " confirm
[[ "$confirm" != "y" ]] && echo "Aborted." && exit 0

pkill -f 'spring-boot' 2>/dev/null || true
pkill -f 'vite'        2>/dev/null || true

docker compose down -v
docker compose up oracle mongodb redis -d

echo "Waiting for Oracle (this takes ~3 minutes first time)..."
while ! docker exec cpintel-oracle healthcheck.sh 2>/dev/null; do
  sleep 5
  echo -n "."
done
echo ""
echo "Databases ready."
