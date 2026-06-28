# CPIntel

Competitive Programming Intelligence Platform that aggregates a user's activity across Codeforces, LeetCode, and CodeChef, then computes topic mastery, contest analytics, spaced-repetition revision schedules, and an adaptive skill-dependency roadmap with live problem recommendations.

## Why this exists

Most competitive programmers track progress across three or four disconnected platforms with no unified view of strengths, weak topics, or what to practice next. CPIntel pulls raw submission history into one place and runs it through an analytics engine (PL/SQL on Oracle) to produce a single mastery score per topic, decay-aware revision reminders, and a real dependency graph of DSA sub-skills with Codeforces problems attached to each node.

## Architecture

React (Vite, TypeScript) talks to Spring Boot 3 / Java 21, which talks to Oracle XE (PL/SQL analytics engine + JPA), MongoDB (raw submissions), Redis (cache, sessions), and the Codeforces / LeetCode / CodeChef integrations (external APIs + HTML scraping).

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full system diagram and [`docs/SRS.md`](docs/SRS.md) for requirements and data model.

## Stack

| Layer | Technology |
|---|---|
| Frontend | React 19, TypeScript, Vite, TailwindCSS, Recharts, Zustand, TanStack Query |
| Backend | Spring Boot 3, Java 21, Spring Security, JWT, Spring Data JPA |
| Primary DB | Oracle XE 21c — PL/SQL packages for mastery scoring, decay, recommendations |
| Document DB | MongoDB — raw submission history per platform |
| Cache | Redis — sessions, JWT blacklist, analytics cache |
| Integrations | Codeforces REST API, LeetCode GraphQL, CodeChef (Jsoup scraping) |
| Migrations | Flyway (16 versioned migrations, 4 PL/SQL packages) |
| Containerization | Docker Compose (7 services) |
| Desktop | Electron wrapper around the same React SPA |
| Monitoring | Prometheus, Grafana, Loki (optional profile) |

## Features

- JWT auth with refresh token rotation, Argon2id password hashing
- Link Codeforces, LeetCode, CodeChef accounts; async background sync of full submission history
- Topic mastery engine: accuracy, volume, recency-decay, and confidence scored per topic via Oracle PL/SQL
- Contest analytics: rating history, wrong-submission patterns, behavioral insight generation
- Unified cross-platform rating, normalized and weighted per platform
- Daily/weekly recommendation sheets and SM-2-style spaced repetition revision queue
- 35-node adaptive roadmap with real prerequisite chains, live-pulled Codeforces problems per node, auto-unlock based on measured mastery
- Dashboards: topic radar, mastery heatmap, rating-over-time, recent contests

## Running locally

### Prerequisites
Docker, Java 21, Node 20, Maven (or use the included `mvnw`).

### 1. Environment
```bash
cp .env.example .env
# fill in DB passwords, JWT secret
```

### 2. Start databases
```bash
docker compose up oracle mongodb redis -d
```
Oracle XE takes 3-5 minutes on first boot. Wait for:
```bash
docker logs -f cpintel-oracle | grep -m1 "DATABASE IS READY TO USE"
```

### 3. Backend
```bash
cd backend
./mvnw spring-boot:run
```
Flyway applies all migrations automatically on startup.

### 4. Frontend
```bash
cd frontend
npm install
npm run dev
```
Open `http://localhost:5173`.

### Full Docker stack (production-style)
```bash
docker compose up -d --build
```
Open `http://localhost`.

### Monitoring (optional)
```bash
docker compose --profile monitoring up -d
```
Grafana on `:3000`, Prometheus on `:9090`.

## API documentation

Swagger UI: `http://localhost:8080/swagger-ui.html`

## Desktop app

Electron dev mode does **not** spawn its own backend or bundle the frontend — it just opens a window pointed at your already-running Vite dev server. Start the backend and frontend exactly as in the steps above first, then:

```bash
cd electron
npm install
npm run dev
```

This opens a desktop window loading `http://localhost:5173` with DevTools open. If you see `Unable to access jarfile .../resources/backend/app.jar`, `NODE_ENV` isn't set to `development` — the `dev` script in `electron/package.json` must run Electron with `NODE_ENV=development` (via `cross-env` for cross-platform safety) so `main.ts` skips spawning the packaged backend.

For a production desktop build (spawns the bundled backend jar and loads the built frontend instead of Vite):

```bash
# from repo root
cd backend && ./mvnw clean package -DskipTests && cd ..
cd frontend && npm run build && cd ..
cd electron && npm run build
```

The packaged app and backend jar/frontend dist are bundled together as Electron resources (see `extraResources` in `electron/package.json`). The Electron shell wraps the same React SPA used on web — no separate frontend codebase.

## Repository structure

```
backend/    Spring Boot API, PL/SQL migrations, integrations
frontend/   React SPA
electron/   Desktop wrapper
nginx/      Reverse proxy config
monitoring/ Prometheus/Grafana/Loki config
docs/       SRS, architecture diagrams, ER diagram
scripts/    Dev convenience scripts
```

## License

MIT
