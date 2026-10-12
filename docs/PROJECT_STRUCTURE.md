# Project Structure and Phase Inventory

## 1. Build and run

- Build: `./mvnw -B package` (Maven Wrapper, Java 21, Spring Boot 3.x).
- API only: `./mvnw -B spring-boot:run -Dspring-boot.run.profiles=api` (default profile).
- Workers: `./mvnw -B spring-boot:run -Dspring-boot.run.profiles=worker` (daemon loop; disabled in the test profile).
- Containerized: `docker compose up --build` (PostgreSQL + Redis + API + worker).
- Tests: `./mvnw -B test` (JUnit 5, embedded PostgreSQL via Zonky, Redis on DB 15).

## 2. Repository layout

```
src/
├── main/java/com/example/jobqueue/
│   ├── api/               # REST controllers, exception handling, health
│   ├── config/            # AppProperties (relaxed binding of app.*)
│   ├── job/               # Job domain: JobRecord, JobService, JobRepository,
│   │                     # JobAttemptRecord, JobStatus, JobMetrics, policies
│   ├── queue/             # RedisQueue, OutboxRepository, OutboxPublisher
│   ├── worker/            # JobExecutor, WorkerService (SmartLifecycle loop)
│   ├── worker/handlers/   # NOOP, FAIL_ONCE, FAIL_ALWAYS, SIMULATE_DELAY,
│   │                     # SIMULATE_EMAIL, GENERATE_REPORT handlers
│   └── benchmark/         # JobLoadTestRunner (HTTP load generator, CLI)
├── main/resources/
│   ├── application.yml                                   # main configuration
│   ├── application-worker.yml / application-test.yml     # profile overrides
│   └── db/migration/V1..V4__*.sql                        # schema + outbox + DLQ
└── test/java/...                                     # unit + integration tests
```

## 3. Phases: what exists where

| Phase | Contents | Files / suites |
|-------|----------|----------------|
| 1 | Foundation | `pom.xml`, `mvnw`, `docker-compose.yml`, `Dockerfile`, `PROJECT_RULES.md`, `README.md` |
| 2 | Persistent job API | `JobRecord`, `JobService`, `JobRepository`, `JobController`, migrations `V1`–`V3`, `JobApiIntegrationTest`, `JobServiceTest` |
| 3 | Workers, retries, DLQ, scheduling | `WorkerService`, `JobExecutor`, handlers, `OutboxPublisher`, `OutboxRepository`, `DueJobDispatcher`, `RedisQueue`, migrations `V4`, REST/queue suites |
| 4 | Validation & portfolio readiness | `WorkerPipelineIntegrationTest` (new), `JobLoadTestRunner`, `docs/` (architecture, structure, AI policy, reproducibility) |

## 4. Test inventory (all phases)

| Suite | Kind | What it covers |
|-------|------|----------------|
| `JobApiIntegrationTest` | Spring Boot / MockMvc | Submit, get, attempts, status transitions, idempotency, invalid payloads |
| `JobServiceTest` | Unit ( Mockito) | Transaction boundaries, idempotency collision, claim/reclaim semantics |
| `JobControllerTest` | Unit ( Mockito) | Controller mapping, error responses, auth-less API surface |
| `BackoffPolicyTest` | Unit | Exponential backoff arithmetic, cap at `maxDelay` |
| `DueJobDispatcherIntegrationTest` | Spring Boot | SCHEDULED/RETRY_WAIT promotion, outbox claim, dispatch lock |
| `OutboxPublisherIntegrationTest` | Spring Boot | Publishing, republish on failure, mark published |
| `HealthControllerTest`, `HealthServiceTest` | Spring Boot | Health probe shape and component statuses |
| `WorkerPipelineIntegrationTest` *(Phase 4)* | Spring Boot + embedded PG + Redis | End-to-end worker loop: success, retry/backoff, DLQ, duplicate claims, crash recovery, attempt persistence |

## 5. Conventions

- Profiles: `api` (default, scheduling enabled), `worker` (`worker.lease`, zero-autostart), `test` (`data.redis.database=15`, short timeouts), `worker+test` (used by integration tests for `@Profile("worker")` beans).
- All time values are ISO-8601 durations (`PT1S`, `P1D`); timestamps are UTC `Instant`/`OffsetDateTime`.
- Queue keys: `jobs:ready`, `jobs:processing:<workerId>`, `jobs:dead-letter`.
- Result payload: JSON stored in `jobs.result`; errors in `jobs.last_error` (capped at 500 chars).
