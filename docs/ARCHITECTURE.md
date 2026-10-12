# Job Queue — Architecture Overview

This document describes the architecture of the `jobqueue` application (Spring Boot 3 / Java 21), its data flow, and the design decisions behind it.

## 1. System components

```
┌─────────────────────────────────────────────────────────────┐
│  Web / API layer                                            │
│  JobController · HealthController · GlobalExceptionHandler  │
└───────────────────────────────┬─────────────────────────────┘
                                │ HTTP / JSON
┌───────────────────────────────▼─────────────────────────────┐
│  Service layer                                              │
│  JobService (submit/claim/reclaim/finish)                   │
│  DueJobDispatcher (scheduled: due→ready)                     │
│  OutboxPublisher (scheduled: outbox→queue)                   │
└───────────────────────────────┬─────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────┐
│  Domain / Repository layer                                  │
│  JobRepository (PostgreSQL via JdbcTemplate)                 │
│  OutboxRepository (PostgreSQL)                              │
│  TaskHandlerRegistry + per-type TaskHandler implementations │
└───────────────────────────────┬─────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────┐
│  Infrastructure                                             │
│  RedisQueue (Redis list: jobs:ready / jobs:processing:<id>   │
│           / jobs:dead-letter)                               │
│  Spring Data Redis StringRedisTemplate                      │
└─────────────────────────────────────────────────────────────┘
```

## 2. Layers and responsibilities

### 2.1 API layer

- `JobController` — REST endpoints (`/api/v1/jobs`, `/api/v1/jobs/{id}`, `/api/v1/health`).
- `HealthController`/`HealthService` — liveness/readiness probe that reports the status of DB, Redis and the queue.
- `GlobalExceptionHandler` + `ApiError` — consistent error shape (`code`, `message`, `details`, `timestamp`).

### 2.2 Service layer

- `JobService` — the single entry point for job state changes:
  - `submit(CreateJobRequest, idempotencyKey)` — insert or idempotent reuse.
  - `claimForExecution(jobId, workerId, leaseUntil)` — atomic lock for workers (PostgreSQL `UPDATE ... RETURNING`).
  - `reclaimInterrupted(jobId, workerId)` — called by a worker on restart to recover its stranded jobs.
  - `finishSucceeded(finishedAt)`, `finishFailed(finishedAt, nextRetryAt)` — end-of-attempt persistence.
- `DueJobDispatcher` — `@Scheduled` loop that picks SCHEDULED jobs whose time has come and RETRY_WAIT jobs whose `next_retry_at` has arrived, promoting them to QUEUED with an outbox record. Uses `pg_advisory_xact_lock` for cross-process safety.
- `OutboxPublisher` — `@Scheduled` loop that reads claimed outbox rows and pushes the corresponding payload into Redis (`jobs:ready`). Retries on transport failure without deleting the outbox row.

### 2.3 Domain layer

- `JobRepository` — all SQL for jobs / attempts / outbox. Query methods are `UPDATE ... RETURNING` under a transaction to make claim and release atomic.
- `JobAttemptRecord` — one row per execution attempt; statuses: `RUNNING`, `SUCCEEDED`, `FAILED`, `INTERRUPTED`.
- `TaskHandlerRegistry` — maps task types (`NOOP`, `FAIL_ONCE`, `FAIL_ALWAYS`, `SIMULATE_DELAY`, `SIMULATE_EMAIL`, `GENERATE_REPORT`) to handlers. New handlers are registered by implementing `TaskHandler`.

### 2.4 Infrastructure layer

- `RedisQueue` — thin wrapper over `StringRedisTemplate` on Redis lists. Methods: `push`, `takeNext` (rightPopAndLeftPush with timeout), `moveOneNow` (rPopLPush for in-place transfer), `acknowledge` (LREM-1), `peekProcessing`, `queueSize`, `clearForTests`.
- `WorkerService` — the worker loop (see next section), implements `SmartLifecycle` so workers start with `autostart=true` and stop cleanly.

## 3. Job lifecycle

```
SUBMITTED (QUEUED) ──DueJobDispatcher──→ SCHEDULED ──sched──→ QUEUED
   ▲                                                       │
   │                                                       ▼
   └────── (next_retry / expiry) ┌──── RETRY_WAIT ─────┘
                                                       │
   QUEUED ──Worker takes message──▶ RUNNING (attempt #) ─┘
   │                                          │
   │  handler.success                       │  handler.fail / RetryableTaskException
   ▼                                          ▼
 SUCCEEDED (job)                         RETRY_WAIT (next_retry_at set)
                                                       │
   DEAD_LETTERED after max_attempts
```

- Every execution of a task creates one `job_attempts` row with `status=RUNNING`. It is closed to `SUCCEEDED`/`FAILED`/`INTERRUPTED`.
- `RetryableTaskException` means "retry with backoff"; a throw after `max_attempts` exhaustions leaves the job `DEAD_LETTERED` and publishes it to `jobs:dead-letter`.
- Backoff: exponential (`baseDelay * 2^(attempt-1)`, capped at `maxDelay`).

## 4. Worker loop

`WorkerService.runLoop()`:

1. `recoverOwnProcessingQueue()` — transfers messages stranded in this worker's own `jobs:processing:<id>` list back to `jobs:ready` and calls `JobService.reclaimInterrupted()` for each one.
2. `takeNext(ready, processing, timeout)` — atomic dequeue.
3. `executor.executeOnce(workerId, jobId, message, redisQueue, processingQueue, lease)`
   - Claim: `JobService.claimForExecution(...)` — succeeds only if the job is QUEUED, or a RUNNING job whose lease expired.
   - Duplicate delivery (two workers take the same message concurrently): the non-claiming side logs and acknowledges the message, so each job executes exactly once.
4. Failure paths: `RetryableTaskException` → partial retry (next attempt < maxAttempts) or DEAD_LETTER; `InterruptedException` → poison handling with interrupt restored.

## 5. Data consistency and failure handling

| Concern                          | Mechanism                                                              |
| -------------------------------- | ---------------------------------------------------------------------- |
| At-most-once execution           | PostgreSQL row lock for claims; RELEASE of lease after success         |
| At-least-once outbox → queue     | Outbox table + `markPublished`; republish on restart                   |
| Lost worker heartbeat            | `lease_until` expiry → next claim re-executes with new attempt number  |
| Worker crash mid-execution       | `reclaimInterrupted` marks attempt `INTERRUPTED`; lease released       |
| Idempotent job submission        | `idempotency_key` unique constraint                                  |
| Poison messages                  | `max_attempts` exhausted → DLQ + `jobs:dead-letter` outbox             |
| Duplicate claims / deliveries    | Atomic claim + duplicate acknowledge + idempotency in the API service  |

## 6. Configuration

See `src/main/resources/application.yml` and `AppProperties.java`; key properties are in the `app` namespace (`app.queue.*`, `app.worker.*`, `app.scheduling.*`, `app.retry.*`). A `worker` Spring profile (`app.worker.autostart=false`) exists for batch/streaming executions that do not run the daemon loop.

## 7. Test coverage (Phase 3–4)

| Suite                                                            | Coverage                                                      |
| ---------------------------------------------------------------- | ------------------------------------------------------------- |
| `JobApiIntegrationTest`, `JobServiceTest`, `JobControllerTest`   | Phase 3 REST / domain edge cases                              |
| `OutboxPublisherIntegrationTest`, `DueJobDispatcherIntegrationTest` | Phase 3 scheduling / outbox                                   |
| `WorkerPipelineIntegrationTest` *(new in this phase)*            | End-to-end worker loop: success, retry/backoff, DLQ, duplicate claims, crash recovery, attempt persistence |

