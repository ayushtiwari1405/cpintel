#!/bin/bash
cd /home/ayush/projects/cpintel

# Start databases
docker compose up oracle mongodb redis -d
echo "Waiting for Oracle..."
docker logs -f cpintel-oracle 2>&1 | grep -m1 "DATABASE IS READY TO USE"

# Start backend
nohup bash -c 'cd backend && ./mvnw spring-boot:run' > /tmp/backend.log 2>&1 &
disown
echo "Backend starting..."
sleep 40

# Start frontend
nohup bash -c 'cd frontend && npx vite --port 5173' > /tmp/frontend.log 2>&1 &
disown
echo "Frontend starting..."
sleep 5

echo ""
echo "CPIntel is running!"
echo "  Frontend: http://localhost:5173"
echo "  Backend:  http://localhost:8080"
echo "  Swagger:  http://localhost:8080/swagger-ui.html"
