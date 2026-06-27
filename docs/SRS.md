# Software Requirements Specification - CPIntel

## 1. Introduction

### 1.1 Purpose
CPIntel is a competitive programming intelligence platform. It aggregates a user's activity from Codeforces, LeetCode, and CodeChef and produces analytics, mastery scoring, and adaptive practice recommendations that no single platform provides on its own.

### 1.2 Scope
In scope: account linking and sync, topic mastery computation, contest analytics, unified cross-platform rating, recommendation generation, spaced-repetition revision scheduling, and an adaptive skill roadmap.

Out of scope: code execution/judging (CPIntel is not an online judge), direct submission to platforms, real-time contest participation tracking during a live contest.

### 1.3 Definitions
- **Mastery score** - 0-100 score per DSA topic derived from accuracy, solve volume, and recency.
- **Decay score** - portion of mastery score considered "faded" due to inactivity, computed via exponential decay.
- **Unified score** - single cross-platform rating combining normalized Codeforces, LeetCode, and CodeChef ratings.
- **Roadmap node** - one DSA sub-skill (e.g. "DFS & BFS") in the 35-node dependency tree.

## 2. Overall description

### 2.1 User classes
- **USER** - standard account, full feature access scoped to their own data.
- **ADMIN** - reserved role for future moderation/ops tooling (not yet exposed in UI).

### 2.2 Operating environment
Backend: Java 21 / Spring Boot 3, deployed via Docker. Frontend: React 19 SPA served via Nginx or Vite dev server, or wrapped in Electron for desktop. Databases: Oracle XE 21c (relational + PL/SQL), MongoDB 7 (document), Redis 7.2 (cache).

## 3. Functional requirements

### FR-1 Authentication
- FR-1.1 Users register with email, username, password (Argon2id hashed).
- FR-1.2 JWT access tokens (15 min) with rotating refresh tokens (7 day) stored server-side and revocable.
- FR-1.3 Logout blacklists the active access token in Redis until natural expiry.

### FR-2 Platform integration
- FR-2.1 Users link one account per platform (Codeforces, LeetCode, CodeChef).
- FR-2.2 Handle existence is validated against the live platform before linking.
- FR-2.3 Initial link triggers a full async sync of submission history into MongoDB.
- FR-2.4 Subsequent syncs are incremental, run on demand or nightly via scheduler.
- FR-2.5 CodeChef has no public JSON API; profile data is obtained via HTML scraping (Jsoup) since this is the only available method.

### FR-3 Topic mastery
- FR-3.1 Mongo-stored submissions are normalized into Oracle `topic_mastery` rows per canonical topic (14 topics: Arrays, Strings, Binary Search, Two Pointers, Greedy, DP, Graphs, Trees, Segment Trees, Binary Lifting, Number Theory, Bit Manipulation, Tries, Geometry).
- FR-3.2 Mastery score is computed in PL/SQL (`PKG_ANALYTICS.calculate_mastery`) from a weighted blend of accuracy (35%), solve volume on a log scale (30%), recency (20%), and difficulty fit (15%).
- FR-3.3 Decay score uses exponential half-life decay (30-day half-life) applied to mastery based on days since last practice in that topic.

### FR-4 Contest analytics
- FR-4.1 Codeforces rating history and per-contest submission stats (wrong-submission count, first-solve time) are pulled and stored as `contest_summaries`.
- FR-4.2 PL/SQL behavioral pattern detection flags users who accumulate penalties disproportionately late in contests.
- FR-4.3 Consistency score is derived from the standard deviation of rating changes across contests.

### FR-5 Unified rating
- FR-5.1 Each platform rating is normalized to a 0-1000 scale using platform-specific min/max bounds.
- FR-5.2 Final unified score is a configurable weighted average (default CF 40%, LC 35%, CC 25%) across only the platforms the user has linked.

### FR-6 Recommendations
- FR-6.1 Daily sheet: top 3 weakest topics with a target difficulty band.
- FR-6.2 Weekly plan: 7 topics prioritized by combined mastery gap and decay.
- FR-6.3 Revision queue: SM-2-inspired spaced repetition; completing a revision increases the interval and ease factor and reduces decay score.

### FR-7 Adaptive roadmap
- FR-7.1 35 DSA sub-skill nodes (e.g. "Two Pointers", "Segment Trees with Lazy Propagation") form a directed prerequisite graph.
- FR-7.2 A node unlocks when all prerequisite nodes' parent-topic mastery reaches >=35%; it is marked complete at >=75% mastery.
- FR-7.3 Each unlocked node displays up to 8 live Codeforces problems filtered by the node's tag set and difficulty band, pulled from the cached CF problemset (`problemset.problems`, 6-hour in-memory TTL) and cross-referenced against the user's solved-problem set.

## 4. Non-functional requirements

- **NFR-1 Performance** - CF problemset cache avoids re-fetching ~10k problems per request; analytics queries are cached in Redis with topic-appropriate TTLs (6-24h).
- **NFR-2 Resilience** - external platform clients retry with exponential backoff (2-3 attempts) and degrade gracefully (sync marked FAILED, not a hard error) if a platform API is unreachable.
- **NFR-3 Security** - stateless JWT auth, CORS restricted per environment, Argon2id (not bcrypt) for password hashing, parameterized queries throughout (no raw SQL concatenation).
- **NFR-4 Portability** - full stack runs via Docker Compose on any OS; frontend SPA is shared unmodified between the web build and the Electron desktop build.

## 5. Data model summary

Relational (Oracle): `users`, `platform_accounts`, `topic_mastery`, `contest_summaries`, `recommendations`, `revision_schedule`, `roadmap_nodes`, `unified_scores`, `refresh_tokens`, `sync_jobs`, `audit_log`.

Document (MongoDB): `cf_submissions`, `lc_submissions`, `cc_submissions`, `contest_snapshots`, `activity_feed`.

Full ER diagram: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md#entity-relationship-diagram).

## 6. External interfaces

| Platform | Method | Notes |
|---|---|---|
| Codeforces | REST (`/api/user.info`, `/user.status`, `/user.rating`, `/problemset.problems`) | Official, stable, rate-limited client-side (500ms) |
| LeetCode | Unofficial GraphQL endpoint | No official public API; schema can change without notice |
| CodeChef | HTML scraping (Jsoup) | No public JSON API exists for user profiles |
