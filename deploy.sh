#!/bin/bash
set -e

echo "=== CPIntel Deploy ==="

# Pull latest images
docker compose pull backend frontend 2>/dev/null || true

# Rolling restart — databases stay up
docker compose up -d --no-deps --build backend frontend nginx

# Wait for backend health
echo "Waiting for backend..."
for i in {1..30}; do
  if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "Backend healthy"
    break
  fi
  sleep 3
done

# Clean up old images
docker image prune -f

echo "Deploy complete at $(date)"
docker compose ps
