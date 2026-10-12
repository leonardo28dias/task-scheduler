# PROJECT_RULES — BE-1 Asynchronous Distributed Task Scheduler & Job Queue Engine

This file records the architectural contract, job states, reliability expectations and
coding rules for the project. Read it before starting each phase. Update it only when an
architectural decision needs clarification.

Current status: **Phase 1 of 4 — foundation and infrastructure.**

---

## 1. Goal

A backend that accepts long-running tasks over HTTP, stores their state in PostgreSQL,
queues them in Redis, and executes them asynchronously on multiple worker containers.
The API returns a job id immediately; workers process jobs in the background and record
results that clients can read through the API.

## 2. Technology stack (pinned)

| Concern            | Choice                                                  |
|--------------------|---------------------------------------------------------|
| Language           | Java 21                                                 |
| Framework          | Spring Boot 3.5.16 (managed via parent BOM)             |
| Build              | Maven with the Maven Wrapper (3.9.16 distribution)      |
| Web                | `spring-boot-starter-web`                               |
| Persistence        | `spring-boot-starter-jdbc` — JdbcTemplate, explicit SQL |
| Queue client       | `spring-boot-starter-data-redis` (Lettuce)              |
| Migrations         | Flyway (`flyway-core` + `flyway-database-postgresql`)   |
| Validation         | `spring-boot-starter-validation` (Bean Validation)      |
| Observability      | `spring-boot-starter-actuator`                          |
| Database           | PostgreSQL 16                                           |
| Broker             | Redis 7 with AOF persistence                            |
| Tests              | JUnit 5, Mockito, MockMvc, AssertJ                      |
| Runtime            | Docker + Docker Compose                                 |

There is **no JPA**. All persistence is explicit SQL through JdbcTemplate so that state
transitions, recovery queries and locking behaviour stay readable.

## 3. Package layout

```
com.example.jobqueue
├── JobQueueApplication.java   # single entry point, runs in API or worker role
├── api/                       # HTTP controllers, DTOs, health reporting
├── config/                    # Spring configuration (introduced when needed)
├── job/                       # job domain: persistence, state transitions
├── queue/                     # Redis queue operations, outbox publisher
└── worker/                    # worker loop, crash recovery, executors
```

Only create classes that the current phase actually needs. No empty scaffolding.

## 4. Coding rules

Prefer: small methods; clear names; straightforward control flow; explicit SQL; normal
Spring Boot patterns; simple DTOs; focused services; clear exceptions; meaningful tests.

Avoid: enormous service classes; overly generic abstractions; unnecessary design
patterns; clever one-liners; unnecessary inheritance; excessive comments; complicated
concurrency abstractions.

## 5. Job states and lifecycle

States stored in `jobs.state` (CHECK constraint in migration V1):

| State           | Meaning                                                    |
|-----------------|------------------------------------------------------------|
| `SCHEDULED`     | Future-dated job, not yet eligible for execution           |
| `QUEUED`        | Message published (or about to be) on `jobs:ready`         |
| `RUNNING`       | Claimed by a worker; `lease_expires_at` is set             |
| `RETRY_WAIT`    | Attempt failed; waiting for backoff, `available_at` is set |
| `SUCCEEDED`     | Terminal success                                            |
| `DEAD_LETTERED` | Terminal failure (permanent error or attempts exhausted)   |

Allowed transitions:

```
(submission, immediate)          -> QUEUED          [job row + outbox row, one transaction]
(submission, future)             -> SCHEDULED
SCHEDULED   -> QUEUED            [scheduled time reached]
QUEUED      -> RUNNING           [worker claims, lease acquired]
RUNNING     -> SUCCEEDED
RUNNING     -> RETRY_WAIT        [retryable failure and attempts remain]
RETRY_WAIT  -> QUEUED            [backoff elapsed, message republished]
RUNNING     -> DEAD_LETTERED     [permanent failure or max attempts exhausted]
RUNNING     -> QUEUED/RETRY_WAIT [lease expired: crash recovery, attempt = INTERRUPTED]
```

Rules:

- Every state change is a guarded update (`UPDATE ... WHERE id = ? AND state = ?`).
  A row that is not in the expected state is ignored, so duplicate queue messages cannot
  re-run completed jobs.
- Terminal states (`SUCCEEDED`, `DEAD_LETTERED`) are never left afterwards.

## 6. Transactional outbox

PostgreSQL is the source of truth. Submitting an immediately executable job writes the
`jobs` row and a `job_outbox` row **in the same transaction**. The outbox publisher later
reads unpublished rows, pushes messages to `jobs:ready`, and marks the rows published.
This keeps a database commit from being lost between submission and queue publication.
Delivery to Redis is at-least-once; consumers must tolerate duplicates (see section 5).

## 7. Redis queue contract

Logical keys:

- `jobs:ready` — messages waiting to be executed
- `jobs:processing:<worker-id>` — messages currently owned by one worker
- `jobs:dead-letter` — failure messages for permanently failed jobs

Operations:

- Consume: blocking pop-and-push equivalent to `BRPOPLPUSH jobs:ready jobs:processing:<worker-id>`.
- Acknowledge: after the final job state is committed in PostgreSQL, remove the message
  from the worker's own processing list (`LREM`).
- PostgreSQL, not Redis, decides whether a message still needs work.

## 8. Worker identity and crash recovery

- Every worker process has a unique `worker-id` (from environment, unique per container).
- Its processing list is `jobs:processing:<worker-id>`.
- On startup, a worker inspects **its own** processing list and recovers messages whose
  job is still unfinished in PostgreSQL.
- PostgreSQL allows reclaim of a job whose `lease_expires_at` has passed. The interrupted
  attempt is recorded in `job_attempts` with outcome `INTERRUPTED`.
- Guarantee: recoverable **at-least-once** processing with safe state transitions.
  The project does **not** claim exactly-once execution of arbitrary external side effects.

## 9. Retries and backoff

- Deterministic exponential backoff:
  `delay = min(baseDelay * 2^(attempt - 1), maxDelay)`.
- Example with base 1 s: attempt 1 → 1 s, attempt 2 → 2 s, attempt 3 → 4 s.
- Base delay, maximum delay and maximum attempts are configurable.
- After the last allowed attempt the job becomes `DEAD_LETTERED` and its failure
  information is published to `jobs:dead-letter`.
- Retryable vs permanent failures are distinguished explicitly by the executor.

## 10. Health checks

`GET /api/v1/health` performs **real** connectivity checks:

- `api` — the process is serving requests;
- `postgres` — `SELECT 1` through JdbcTemplate;
- `redis` — `PING` through the Redis connection factory.

Response: HTTP `200` with `"status": "UP"` when every component is up, otherwise HTTP
`503` with `"status": "DOWN"` plus a per-component message. No hardcoded healthy answers.

## 11. Configuration and secrets

- All configuration comes from environment variables with local-dev defaults in
  `application.yml` (see README for the list).
- `.env` is gitignored; only `.env.example` is committed; no credentials in code.
- Database name, username, password, JDBC URL, Redis host/port and application port are
  all injectable.

## 12. Database schema (migration V1)

- `jobs` — metadata, state, attempt counters, `scheduled_at`, `available_at`,
  `lease_expires_at`, `last_error`, `result`.
- `job_outbox` — pending queue publications (`published_at IS NULL` = pending).
- `job_attempts` — execution history: one row per attempt with worker id, timing,
  outcome (`SUCCEEDED`, `FAILED`, `INTERRUPTED`) and error message.

## 13. Testing rules

- `./mvnw test` must pass without external infrastructure: unit tests use JUnit 5,
  Mockito and `@WebMvcTest`; nothing in the default test suite opens a socket to
  PostgreSQL or Redis.
- Integration behaviour is verified manually against the Docker Compose environment
  (documented in the README).
- Do not claim a command succeeded unless it was executed and its output observed.

## 14. Delivery phases

The build follows four sequential prompts from the project owner:

1. **Phase 1 (this state):** project foundation, Docker infrastructure, health checks,
   architecture documentation. No job submission, workers or retries yet.
2. Phase 2: job submission API, transactional outbox publisher, scheduled-job promotion.
3. Phase 3: workers, queue processing, retries with backoff, dead-letter handling,
   crash recovery.
4. Phase 4: execution metrics, hardening, final documentation and tests.


