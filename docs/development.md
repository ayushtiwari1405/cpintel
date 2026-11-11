# Development

Running CPIntel on your own machine, and the API documentation.

## Running locally

### Prerequisites

Docker, Java 21, Node 24, Maven (or use the included `mvnw`).

### 1. Environment

```bash
cp .env.example .env
# fill in DB passwords (MONGO_APP_PASSWORD too: the backend's own MongoDB account) and a JWT secret
```

### 2. Start the databases

```bash
docker compose up postgres mongodb redis -d
docker compose ps postgres   # STATUS should read (healthy)
scripts/mongo-app-user.sh    # a database made before the backend had its own account gets it
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

### Full Docker stack, built from source

```bash
docker compose up -d --build     # open https://localhost — a temporary self-signed certificate
```


## API documentation

Swagger UI: `http://localhost:8080/swagger-ui.html` (development; the `prod` profile serves
neither it nor `/v3/api-docs`).

### API versioning

Every route lives under `/api/v1`. The version is in the path rather than a header so that a
request can be read, logged and curl'd without extra ceremony, and so a proxy can route on it.

`GET /api/version` is unversioned and needs no authentication — it reports the current and
minimum API versions plus the minimum desktop build the server will accept. The browser SPA is
served from the same origin and cannot disagree with the API, but the desktop app is packaged
and installed separately, so it can be arbitrarily far behind; this is how it finds out before
it fails on a route that no longer exists.

Everything under `/api/admin/**` requires the ADMIN role, enforced in the security filter chain
as well as on each controller.
