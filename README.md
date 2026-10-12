# BE-1 — Asynchronous Distributed Task Scheduler & Job Queue Engine

Backend service that accepts long-running jobs over HTTP, stores job state in
PostgreSQL, queues work in Redis and executes it asynchronously on multiple workers.

**Status: Phase 4 of 4 — validation, load testing, benchmarking and portfolio documentation.**

The full architectural contract (job states, queue keys, retry rules) lives in
[PROJECT_RULES.md](PROJECT_RULES.md).

---

## 1. Architecture

```
CLIENT
  |  POST /api/v1/jobs                      (Phase 2)
  v
+-------------------+      +---------------------------------+
| Spring Boot API   | ---> | PostgreSQL                      |
+-------------------+      | job metadata / execution history|
        |                  | transactional outbox            |
        | outbox publisher +---------------------------------+
        |                        (Phase 2)  |
        v                                   v
+-------------------+      jobs:ready / jobs:processing:<id> / jobs:dead-letter
|       Redis       | <------------------------------+
+-------------------+                               |
                          +-----------+-------------+
                          |           |           |
                       Worker 1    Worker 2    Worker 3      (Phase 3)
                          |           |           |
                          +-----------+-----------+
                                      v
                              PostgreSQL (state, results, attempts)
```

- **API** — `JobController` (jobs, attempts), `HealthController` (dependency probes),
  `GlobalExceptionHandler` (structured `ApiError` responses).
- **Service** — `JobService` (submit/claim/reclaim/finish), `DueJobDispatcher` (scheduled
  dispatch), `OutboxPublisher` (publish & retry).
- **Domain** — `JobRepository` (row-level locking claims), `JobAttemptRecord`
  (one row per attempt), `TaskHandlerRegistry` + handlers.
- **Infrastructure** — `RedisQueue` (Redis lists), Spring Data Redis, PostgreSQL.
- See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the job lifecycle, worker loop,
  consistency matrix and failure handling.

## 2. Quick start

Prerequisites: Docker with the Compose plugin.

```bash
cp .env.example .env            # adjust values if needed
docker compose up --build -d
docker compose ps               # wait until postgres/redis are healthy and api is running
curl http://localhost:8080/api/v1/health
```

Example response (HTTP `200`):

```json
{
  "status": "UP",
  "timestamp": "2026-10-11T20:00:00Z",
  "components": {
    "api": { "status": "UP" },
    "postgres": { "status": "UP" },
    "redis": { "status": "UP" }
  }
}
```

Stop and clean up:

```bash
docker compose down       # stop containers, keep volumes
docker compose down -v    # stop containers, delete PostgreSQL/Redis data
```

## 3. API

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/jobs` | Submit a job (idempotent with `Idempotency-Key` header) |
| GET | `/api/v1/jobs/{id}` | Job details (`status`, `attemptCount`, `result`, `lastError`) |
| GET | `/api/v1/jobs/{id}/attempts` | Attempt history |
| GET | `/api/v1/health` | Dependency health (503 when a dependency is down) |

**Submission example** (`curl -i -X POST localhost:8080/api/v1/jobs -H 'Content-Type: application/json' -d '{"type":"NOOP","payload":{},"maxAttempts":3}'`):

```json
{
  "jobId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "type": "NOOP",
  "status": "QUEUED",
  "attemptCount": 0,
  "maxAttempts": 3
}
```

Successful completion stores the result JSON in `jobs.result` (e.g. `{"ok":true}` for
`NOOP`). Failures store the error in `jobs.last_error`, and after `maxAttempts`
attempts the job becomes `DEAD_LETTERED` with an outbox copy in
`jobs:dead-letter`.

## 4. Configuration

All configuration comes from environment variables; defaults in
`src/main/resources/application.yml` target local development. `.env` is gitignored,
only `.env.example` (placeholders) is committed — no secrets in the repository.

| Variable | Used by | Default | Purpose |
|----------|---------|---------|---------|
| `POSTGRES_DB` | postgres | `jobqueue` | Database name |
| `POSTGRES_USER` | postgres + api | `jobqueue` | Database user |
| `POSTGRES_PASSWORD` | postgres + api | — (required) | Database password |
| `DATABASE_URL` | api | `jdbc:postgresql://postgres:5432/jobqueue` | JDBC URL |
| `REDIS_HOST` | api | `redis` | Redis host (compose network) |
| `REDIS_PORT` | api | `6379` | Redis port |
| `POSTGRES_HOST_PORT` | host | `5432` | Host port mapped to PostgreSQL |
| `REDIS_HOST_PORT` | host | `6379` | Host port mapped to Redis (raise it if occupied) |
| `APP_PORT` | host | `8080` | Host port mapped to the API |
| `SERVER_PORT` | api | `8080` | API listening port |

The API also honours the standard Spring overrides (`SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_HOST`,
`SPRING_DATA_REDIS_PORT`) — Compose maps the variables above onto them.

### Profiles

| Profile | Purpose |
|---------|---------|
| `api` (default) | Scheduling enabled, web app |
| `worker` | Zero-autostart daemon loop, `app.flyway.enabled` disabled by design (overridden in tests) |
| `test` | Redis database 15, short timeouts |

## 5. Task types

`NOOP` | `FAIL_ONCE` (retry once, backoff) | `FAIL_ALWAYS` (dead-letter after max attempts) |
`SIMULATE_DELAY` (delays seconds, interruptible) | `SIMULATE_EMAIL` | `GENERATE_REPORT`.

## 6. Test inventory (all phases)

| Suite | Kind | What it covers |
|-------|------|----------------|
| `JobApiIntegrationTest` | Spring Boot / MockMvc | Submit, get, attempts, status transitions, idempotency, invalid payloads |
| `JobServiceTest` | Unit ( Mockito) | Transaction boundaries, idempotency collision, claim/reclaim semantics |
| `JobControllerTest` | Unit ( Mockito) | Controller mapping, error responses, API surface |
| `BackoffPolicyTest` | Unit | Exponential backoff arithmetic, cap at `maxDelay` |
| `DueJobDispatcherIntegrationTest` | Spring Boot | SCHEDULED/RETRY_WAIT promotion, outbox claim, dispatch lock |
| `OutboxPublisherIntegrationTest` | Spring Boot | Publishing, republish on failure, mark published |
| `HealthControllerTest`, `HealthServiceTest` | Spring Boot | Health probe shape and component statuses |
| `WorkerPipelineIntegrationTest` *(Phase 4)* | Spring Boot + embedded PG + Redis | End-to-end worker loop: success, retry/backoff, DLQ, duplicate claims, crash recovery, attempt persistence |

Run: `./mvnw -B test`. Reports: `target/surefire-reports/`.

## 7. Phase 4: Load testing and benchmarking

The load runner is a standalone HTTP client that submits jobs concurrently, waits for
every job to reach a terminal state and reports throughput:

```bash
# 1. start api + 3 workers in containers (see section 9)
# 2. run the benchmark
java -jar target/jobqueue-0.1.0-SNAPSHOT.jar \\
  http://localhost:8080 1200 noop 3   # base-url, jobs=1200, type=NOOP, maxAttempts=3
```

Output: `submitted=1200 succeeded=X deadLettered=0 unfinished=0 jobsPerMinute=...`.

**Phase 4 requirement:** ≥ 1200 jobs/minute sustained across 3 workers, zero unfinished
and zero inconsistent states. `JobLoadTestRunner` exits non-zero on unfinished or
inconsistent results, so the same gates can be reused for CI.

## 8. Deployment

- **Docker**: multi-stage build (`Dockerfile`) + Compose (`docker-compose.yml`) bringing up
  PostgreSQL, Redis, the API and configurable worker count. All services expose health
  checks.
- **Local without Docker**: `./mvnw spring-boot:run` (defaults in `application.yml`), or
  `./mvnw test` for the full suite — no external infrastructure needed.

## 9. Implementation conventions

- Freeze the infrastructure: `api` (default), `worker` (daemon), `test` (Redis DB 15).
- Times are ISO-8601 durations (`PT1S`); timestamps are UTC `Instant`/`OffsetDateTime`.
- Keys: `jobs:ready`, `jobs:processing:<workerId>`, `jobs:dead-letter`.
- One `job_attempts` row per attempt (`RUNNING` → `SUCCEEDED`/`FAILED`/`INTERRUPTED`).
- `RetryableTaskException` = retry with backoff; after `maxAttempts` exhaustion the job
  is `DEAD_LETTERED` and published to the dead letter queue.
- Results are JSON (`jobs.result`), errors are capped at 500 characters (`jobs.last_error`).
- New task handlers: implement `TaskHandler` and register in `TaskHandlerRegistry`.

## 10. Phase 4 milestones and evidence

| Milestone | Evidence |
|-----------|----------|
| Correctness audit (Phase 1–3) | `./mvnw -B test` exits 0; 9 suites, 0 failures |
| Worker pipeline suite | `WorkerPipelineIntegrationTest` (5 tests): success, retry, DLQ, duplicate claims, crash recovery |
| Load benchmark | `JobLoadTestRunner` ≥ 1200 jobs/min on 3 workers, all jobs terminal, 0 unfinished |
| Automated review | Every generated test reviewed against `assertj` + surefire |
| Documentation | `docs/ARCHITECTURE.md`, `PROJECT_STRUCTURE.md`, `AI_POLICY.md`, `REPRODUCIBILITY.md` |

## 11. AI usage policy

This phase used AI assistance (see [docs/AI_POLICY.md](docs/AI_POLICY.md)) to draft
integration tests, generate the architecture overview and interpret validation
failures. Every generated file was read and manually validated against the codebase —
AI never changed business rules or touched files outside this repository.

## 12. Reproducibility

Rebuild and re-run everything from scratch: `./mvnw -B clean verify` followed by
`./mvnw -B test`. Machine profile (Java 21, Docker Compose version) is captured in each
benchmark results file rather than averaged away. Full checklist: [docs/REPRODUCIBILITY.md](docs/REPRODUCIBILITY.md).

New types are registered in `TaskHandlerRegistry` by implementing `TaskHandler`.
