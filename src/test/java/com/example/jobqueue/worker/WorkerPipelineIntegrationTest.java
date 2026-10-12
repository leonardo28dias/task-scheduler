package com.example.jobqueue.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.example.jobqueue.config.AppProperties;
import com.example.jobqueue.job.JobAttemptRecord;
import com.example.jobqueue.job.JobAttemptStatus;
import com.example.jobqueue.job.JobRepository;
import com.example.jobqueue.job.JobService;
import com.example.jobqueue.queue.RedisQueue;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.CountDownLatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles({"worker", "test"})
class WorkerPipelineIntegrationTest {

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        // The worker profile disables Flyway; the test needs the schema applied.
        registry.add("spring.flyway.enabled", () -> "true");
        // Disabling the worker daemon keeps the test suite deterministic: JobExecutor executes
        // the same claim->attempt->finish pipeline the daemon loop runs, without queue
        // contention from the background polling loop.
        registry.add("app.worker.autostart", () -> "false");
        registry.add("app.worker.lease", () -> "PT30S");
        registry.add("app.queue.consume-timeout", () -> "PT1S");
        registry.add("app.retry.base-delay", () -> "PT0.2S");
        registry.add("app.retry.max-delay", () -> "PT1S");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository repository;

    @Autowired
    private JobExecutor executor;

    @Autowired
    private RedisQueue redisQueue;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private AppProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String readyQueue;
    private String processingQueue;

    @BeforeEach
    void cleanState() {
        readyQueue = properties.queue().ready();
        processingQueue = properties.queue().processingPrefix() + workerService.workerId();
        jdbcTemplate.update("DELETE FROM job_attempts");
        jdbcTemplate.update("DELETE FROM outbox_messages");
        jdbcTemplate.update("DELETE FROM jobs");
        redisTemplate.delete(readyQueue);
        redisTemplate.delete(processingQueue);
        redisTemplate.delete(properties.queue().deadLetter());
    }


    @Test
    void noopJobIsConsumedAndCompletesWithResult() {
        UUID jobId = insertQueuedJob("NOOP", "{}", 3);

        boolean executed = runOneCycle();

        assertThat(executed).isTrue();
        assertThat(jobStatus(jobId)).isEqualTo("SUCCEEDED");
        assertThat(jobAttempts(jobId)).isEqualTo(1);
        assertThat(attemptStatus(jobId, 1)).isEqualTo("SUCCEEDED");
        assertThat(processingSize()).isZero();
        String result = jdbcTemplate.queryForObject(
                "SELECT result FROM jobs WHERE id = ?", String.class, jobId);
        assertThat(result).isNotBlank();
    }

    private boolean runOneCycle() {
        pushReadyForQueuedJobs();
        RedisQueue.QueueMessage message = redisQueue.takeNext(
                readyQueue, processingQueue, Duration.ofSeconds(2));
        if (message == null) {
            return false;
        }
        // A message was consumed and an attempt executed. executeOnce() returns false both for
        // a duplicate/empty claim and for a failed attempt that schedules a retry, so we cannot
        // use its return value here; the resulting job status is asserted by each test instead.
        executor.executeOnce(workerService.workerId(), message.jobId(), message,
                redisQueue, processingQueue, Duration.ofSeconds(30));
        return true;
    }

    private void pushReadyForQueuedJobs() {
        jdbcTemplate.queryForList("SELECT id FROM jobs WHERE status = 'QUEUED'")
                .forEach(row -> pushReadyMessage((UUID) row.get("id")));
    }

    void pushReadyMessage(UUID jobId) {
        redisQueue.push(readyQueue,
                new RedisQueue.QueueMessage(UUID.randomUUID(), jobId, Instant.now()));
    }

    String jobStatus(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM jobs WHERE id = ?", String.class, jobId);
    }

    int jobAttempts(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM jobs WHERE id = ?", Integer.class, jobId);
    }

    String attemptStatus(UUID jobId, int attemptNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM job_attempts WHERE job_id = ? AND attempt_number = ?",
                String.class, jobId, attemptNumber);
    }

    OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @Test
    void failOnceRetriesWithBackoffThenSucceeds() {
        UUID jobId = insertQueuedJob("FAIL_ONCE", "{}", 3);

        assertThat(runOneCycle()).isTrue();
        assertThat(jobStatus(jobId)).isEqualTo("RETRY_WAIT");
        Instant firstRetryAt = nextRetryAt(jobId);
        assertThat(firstRetryAt).isAfter(Instant.now());

        jdbcTemplate.update(
                "UPDATE jobs SET status = 'QUEUED', next_retry_at = NULL WHERE id = ?", jobId);
        assertThat(runOneCycle()).isTrue();

        assertThat(jobStatus(jobId)).isEqualTo("SUCCEEDED");
        assertThat(jobAttempts(jobId)).isEqualTo(2);
        assertThat(attemptStatus(jobId, 1)).isEqualTo("FAILED");
        assertThat(attemptStatus(jobId, 2)).isEqualTo("SUCCEEDED");
    }

    @Test
    void failAlwaysReachesDeadLetterAfterMaxAttempts() {
        UUID jobId = insertQueuedJob("FAIL_ALWAYS", "{}", 3);

        for (int cycle = 0; cycle < 3; cycle++) {
            assertThat(runOneCycle()).isTrue();
            String status = jobStatus(jobId);
            if ("DEAD_LETTERED".equals(status)) {
                break;
            }
            assertThat(status).isEqualTo("RETRY_WAIT");
            jdbcTemplate.update(
                    "UPDATE jobs SET status = 'QUEUED', next_retry_at = NULL WHERE id = ?", jobId);
        }

        assertThat(jobStatus(jobId)).isEqualTo("DEAD_LETTERED");
        assertThat(jobAttempts(jobId)).isEqualTo(3);
        Integer deadLetters = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE destination = 'jobs:dead-letter'",
                Integer.class);
        assertThat(deadLetters).isEqualTo(1);
    }

    UUID insertQueuedJob(String type, String payload, int maxAttempts) {
        Instant now = Instant.now();
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO jobs (id, type, payload, status, created_at, updated_at,
                                  attempt_count, max_attempts)
                VALUES (?, ?, ?::jsonb, 'QUEUED', ?, ?, 0, ?)
                """,
                jobId, type, payload, utc(now), utc(now), maxAttempts);
        return jobId;
    }

    @Test
    void concurrentClaimsOnSameJobNeverExecuteTwice() throws Exception {
        UUID jobId = insertQueuedJob("NOOP", "{}", 3);
        pushReadyMessage(jobId);

        RedisQueue.QueueMessage message = redisQueue.takeNext(
                readyQueue, processingQueue, Duration.ofSeconds(2));
        assertThat(message).isNotNull();

        CountDownLatch done = new CountDownLatch(2);
        Thread workerA = new Thread(() -> {
            executor.executeOnce(workerService.workerId(), jobId, message,
                    redisQueue, processingQueue, Duration.ofSeconds(30));
            done.countDown();
        }, "worker-a");
        Thread workerB = new Thread(() -> {
            executor.executeOnce(workerService.workerId(), jobId, message,
                    redisQueue, processingQueue, Duration.ofSeconds(30));
            done.countDown();
        }, "worker-b");
        workerA.start();
        workerB.start();
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(jobStatus(jobId)).isEqualTo("SUCCEEDED");
        assertThat(jobAttempts(jobId)).isEqualTo(1);
        assertThat(processingSize()).isZero();
    }

    @Test
    void crashRecoveryRestoresJobAndPreservesAttempts() throws Exception {
        UUID jobId = insertQueuedJob("SIMULATE_DELAY", "{\"seconds\":1}", 3);
        String crashedWorker = "crashed-worker";

        pushReadyMessage(jobId);
        // Move non-blockingly: takeNext(..., Duration.ZERO) would issue BRPOPLPUSH ... 0, which
        // blocks forever when the ready queue is empty and would hang the whole suite.
        RedisQueue.QueueMessage stranded = redisQueue.moveOneNow(
                readyQueue, properties.queue().processingPrefix() + crashedWorker);
        assertThat(stranded).isNotNull();

        // The crashed worker had started the attempt (RUNNING with a future lease).
        Instant leaseUntil = Instant.now().plus(Duration.ofHours(1));
        jdbcTemplate.update("""
                UPDATE jobs
                SET status = 'RUNNING', worker_id = ?, lease_until = ?,
                    started_at = COALESCE(started_at, now()), attempt_count = attempt_count + 1
                WHERE id = ?
                """, crashedWorker, OffsetDateTime.ofInstant(leaseUntil, ZoneOffset.UTC), jobId);
        jdbcTemplate.update("""
                INSERT INTO job_attempts (job_id, attempt_number, worker_id, status, started_at)
                VALUES (?, (SELECT attempt_count FROM jobs WHERE id = ?), ?, 'RUNNING', now())
                """, jobId, jobId, crashedWorker);

        // Crash: the lease is forgotten so the attempt becomes reclaimable, but the
        // attempt row stays on disk so it persists across worker restart.
        repository.reclaimInterrupted(jobId, crashedWorker);

        // Worker-loop recovery: return the stranded message to ready and execute it.
        // The expired lease lets the same claim trip again.
        RedisQueue.QueueMessage recovered = redisQueue.moveOneNow(
                properties.queue().processingPrefix() + crashedWorker, readyQueue);
        assertThat(recovered).isNotNull();

        boolean executed = executor.executeOnce(
                workerService.workerId(), recovered.jobId(), recovered,
                redisQueue, processingQueue, Duration.ofSeconds(30));
        assertThat(executed).isTrue();

        assertThat(jobStatus(jobId)).isEqualTo("SUCCEEDED");
        List<JobAttemptRecord> attempts = repository.findAttempts(jobId);
        // reclaimInterrupted() marks the crashed worker's RUNNING attempt as INTERRUPTED in place
        // (markInterruptedAttempts is an UPDATE, so no separate RUNNING row survives the crash);
        // recovery then re-claims and completes a new attempt as SUCCEEDED.
        assertThat(attempts).extracting(record -> record.status())
                .containsExactly(JobAttemptStatus.INTERRUPTED,
                        JobAttemptStatus.SUCCEEDED);
        assertThat(attempts).hasSize(2);
        int attemptCount = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM jobs WHERE id = ?", Integer.class, jobId);
        assertThat(attemptCount).isEqualTo(2);
        String result = jdbcTemplate.queryForObject(
                "SELECT result FROM jobs WHERE id = ?", String.class, jobId);
        assertThat(result).isNotBlank();
    }

    private Instant nextRetryAt(UUID jobId) {
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT next_retry_at FROM jobs WHERE id = ?", OffsetDateTime.class, jobId);
        return value == null ? null : value.toInstant();
    }

    private long processingSize() {
        return redisQueue.queueSize(processingQueue);
    }
}
