# Architecture

## System overview

```mermaid
flowchart LR
    subgraph Client
        FE[React SPA<br/>Vite + TypeScript]
        EL[Electron shell]
    end

    subgraph Backend [Spring Boot 3 / Java 21]
        API[REST controllers]
        SVC[Service layer]
        ANL[Analytics engine]
        SCH[Schedulers]
    end

    subgraph Data
        ORA[(Oracle XE<br/>PL/SQL packages)]
        MON[(MongoDB<br/>raw submissions)]
        RED[(Redis<br/>cache + sessions)]
    end

    subgraph External
        CF[Codeforces API]
        LC[LeetCode GraphQL]
        CC[CodeChef - scraped]
    end

    EL --> FE
    FE -- HTTPS/JWT --> API
    API --> SVC
    SVC --> ANL
    SVC --> ORA
    SVC --> MON
    SVC --> RED
    ANL --> ORA
    SCH --> SVC
    SVC --> CF
    SVC --> LC
    SVC --> CC
```

## Request flow - linking a platform account

```mermaid
sequenceDiagram
    participant U as User
    participant FE as React SPA
    participant API as Spring Boot
    participant EXT as Platform API
    participant MDB as MongoDB
    participant ORA as Oracle

    U->>FE: Enter CF handle, click Link
    FE->>API: POST /integrations/codeforces/link
    API->>EXT: Validate handle exists
    EXT-->>API: 200 OK
    API->>ORA: INSERT platform_accounts
    API-->>FE: 200, sync job queued
    API->>EXT: Fetch full submission history (async)
    API->>MDB: Bulk insert cf_submissions
    API->>ORA: Update topic_mastery via PKG_ANALYTICS
    FE->>API: Poll /integrations/sync-status/{jobId}
    API-->>FE: COMPLETED, itemsSynced
```

## Roadmap unlock logic

```mermaid
flowchart TD
    A[Node has prereqIds] --> B{All prereqs<br/>mastery >= 35%?}
    B -- No --> L[Status: LOCKED]
    B -- Yes --> C{Own topic<br/>mastery >= 75%?}
    C -- Yes --> D[Status: COMPLETED]
    C -- No --> E{Own mastery > 0?}
    E -- Yes --> F[Status: IN_PROGRESS]
    E -- No --> G[Status: UNLOCKED]
```

## Entity-relationship diagram

```mermaid
erDiagram
    USERS ||--o{ PLATFORM_ACCOUNTS : links
    USERS ||--o{ TOPIC_MASTERY : has
    USERS ||--o{ CONTEST_SUMMARIES : has
    USERS ||--o{ ROADMAP_NODES : has
    USERS ||--o{ REVISION_SCHEDULE : has
    USERS ||--|| UNIFIED_SCORES : has
    USERS ||--o{ REFRESH_TOKENS : issues
    USERS ||--o{ SYNC_JOBS : triggers

    USERS {
        number user_id PK
        varchar username
        varchar email
        varchar password_hash
        varchar role
    }
    PLATFORM_ACCOUNTS {
        number account_id PK
        number user_id FK
        varchar platform
        varchar handle
        number current_rating
    }
    TOPIC_MASTERY {
        number mastery_id PK
        number user_id FK
        varchar topic
        number mastery_score
        number decay_score
    }
    CONTEST_SUMMARIES {
        number contest_id PK
        number user_id FK
        varchar platform
        number rating_change
    }
    ROADMAP_NODES {
        number node_id PK
        number user_id FK
        varchar node_key
        varchar status
        varchar prereq_keys
    }
    UNIFIED_SCORES {
        number score_id PK
        number user_id FK
        number unified_score
    }
```

## Why Oracle + PL/SQL for analytics

The mastery, decay, and recommendation logic runs as PL/SQL packages (`PKG_ANALYTICS`, `PKG_RECOMMENDATION`, `PKG_CONTEST`, `PKG_UNIFIED_RATING`) rather than in the Java service layer. This keeps the scoring formulas colocated with the data they read, lets them run inside a single transaction via `SimpleJdbcCall`, and allows Oracle's `DBMS_SCHEDULER` to run nightly recompute jobs independent of application uptime.

## Why MongoDB for submissions

Codeforces, LeetCode, and CodeChef submission payloads have different shapes and change independently of each other. Storing them as loosely-typed documents avoids a brittle shared relational schema, while normalized aggregates (topic mastery, contest summaries) still live in Oracle once computed.
