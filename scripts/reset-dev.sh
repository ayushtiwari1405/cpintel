#!/bin/bash
echo "=== Resetting CPIntel dev environment ==="

# Kill running processes
pkill -f 'spring-boot'  2>/dev/null || true
pkill -f 'vite'         2>/dev/null || true
sleep 2

# Restart only databases (keep data)
docker compose restart oracle mongodb redis

echo "Waiting for databases..."
sleep 10
docker compose ps

echo ""
echo "Databases ready. Start with:"
echo "  cd backend  && ./mvnw spring-boot:run"
echo "  cd frontend && npm run dev"
