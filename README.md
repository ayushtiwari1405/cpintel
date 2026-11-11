# CPIntel

Competitive Programming Intelligence Platform. It reads a user's Codeforces history and turns it
into topic skill ratings, contest analytics, a revision schedule and a 140-node adaptive skill
roadmap. It then gives them somewhere to practise and compete: an editor, a sandboxed code
runner, and admin-run contests and invigilated examinations on Codeforces or DOMjudge.

## What it does

- **Analytics and planning:** per-topic skill ratings, contest analytics, a daily and weekly
  practice plan with spaced revision, and a roadmap that unlocks as mastery grows.
- **Practice and compete:** one split-pane workspace (statement, editor, test console) for
  Codeforces and DOMjudge, with local Run against the samples before a real submission.
- **Classrooms and teams:** each classroom owns one DOMjudge server, its students, teams,
  contests and question bank.
- **Examinations:** assigned papers sat with a per-paper password, monitored, held to their own
  window, with their own leaderboard, a session log and optional desktop-only lockdown.
- **Desktop app:** an Electron window onto the server, with exam lockdown and a Codeforces
  sign-in window of its own.

The full list is in [docs/features.md](docs/features.md).

## Stack

React 19 + TypeScript (Vite) talks to Spring Boot 3 / Java 21, backed by PostgreSQL 16 (system
of record, Flyway — 33 versioned migrations), MongoDB (submissions, files), and Redis (sessions,
cache). Code runs in a locked-down runner container (bubblewrap + rlimits). Everything ships as
Docker Compose services behind nginx. Optional monitoring uses Prometheus, Grafana and Loki.

## Quick start (local)

Needs Docker, Java 21 and Node 24.

```bash
cp .env.example .env          # fill in the database passwords and JWT_SECRET
./start.sh                    # databases, backend and frontend
```

Open `http://localhost:5173`. The step-by-step version is in
[docs/development.md](docs/development.md).

## Deploying

```bash
python3 -I scripts/deploy-preflight.py --version <commit-sha>
./deploy.sh <commit-sha>
```

Before the first deploy, the server needs a few things the commands above do not cover: read
access to the repository, a GHCR login, a filled-in `.env`, and one kernel setting on Ubuntu
24.04. The full walkthrough, from an empty server to the first sign-in, is in
[docs/deployment.md](docs/deployment.md). What the college has to provide is the checklist in
[docs/requirements.md](docs/requirements.md).

## Documentation

| Guide | What it covers |
|---|---|
| [Deployment](docs/deployment.md) | Server setup, GHCR access, `.env`, deploy and rollback, first use |
| [Requirements](docs/requirements.md) | What the college provides: server, network, DNS, accounts |
| [Configuration](docs/configuration.md) | Every environment variable and secret |
| [Operations](docs/operations.md) | MongoDB accounts and upgrades, backups and restore, health, monitoring |
| [Accounts](docs/accounts.md) | Roles, passwords, the first super admin |
| [Classrooms](docs/classrooms.md) | Classrooms, rosters, team contests, the question bank |
| [Examinations](docs/examinations.md) | Running an exam end to end, rehearsals, language limits |
| [Codeforces](docs/codeforces.md) | Connecting Codeforces from the desktop app or the website |
| [Desktop app](docs/desktop-app.md) | Building, publishing and updating the installers |
| [Code runner](docs/code-runner.md) | How Run works, the sandbox, languages |
| [Code visualiser](docs/visualiser.md) | Stepping through C++ and Python for teaching: views, how tracing works, limits |
| [Development](docs/development.md) | Running locally, API documentation |
| [Testing](docs/testing.md) | Test suites, integration and smoke tests, CI, release readiness |
| [Features](docs/features.md) | Everything the product does, and where it is in the app |
| [Architecture](docs/ARCHITECTURE.md) | System and ER diagrams, design decisions |
| [SRS](docs/SRS.md) | Requirements specification |

## Repository structure

```
backend/    Spring Boot API, Flyway migrations, analytics engine, integrations
frontend/   React SPA — pages, the shared workspace components, hooks
electron/   Desktop wrapper, examination monitoring and the Codeforces window
extension/  Browser extension build (source in frontend/extension-src)
deploy/     The runner container's seccomp profile
nginx/      Reverse proxy config
monitoring/ Prometheus/Grafana/Loki config
docs/       The guides above
scripts/    Backup/restore, deploy preflight, local CI, simulations, dev helpers
```

## License

MIT
