package com.example.jobqueue.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {

    private static final String JOB_COLUMNS = """
            id, type, payload, status, scheduled_at, created_at, updated_at, started_at,
            completed_at, attempt_count, max_attempts, next_retry_at, result, last_error,
            worker_id, lease_until, idempotency_key
            """;

    private final JdbcTemplate jdbcTemplate;

    public JobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertJob(JobRecord job) {
        jdbcTemplate.update("""
                INSERT INTO jobs (id, type, payload, status, scheduled_at, created_at, updated_at,
                                  attempt_count, max_attempts, idempotency_key)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, 0, ?, ?)
                """,
                job.id(),
                job.type(),
                job.payload(),
                job.status().name(),
                toUtc(job.scheduledAt()),
                toUtc(job.createdAt()),
                toUtc(job.updatedAt()),
                job.maxAttempts(),
                job.idempotencyKey());
    }

    public void insertOutbox(UUID jobId, OutboxDestination destination, String payload) {
        jdbcTemplate.update("""
                INSERT INTO outbox_messages (job_id, destination, payload, created_at, publish_attempts)
                VALUES (?, ?, ?::jsonb, ?, 0)
                """,
                jobId,
                destination.queue(),
                payload,
                toUtc(Instant.now()));
    }

    public Optional<JobRecord> findById(UUID jobId) {
        List<JobRecord> jobs = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM jobs WHERE id = ?", jobRowMapper(), jobId);
        return jobs.stream().findFirst();
    }

    public Optional<JobRecord> findByIdempotencyKey(String idempotencyKey) {
        List<JobRecord> jobs = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM jobs WHERE idempotency_key = ?",
                jobRowMapper(), idempotencyKey);
        return jobs.stream().findFirst();
    }

    public Optional<ClaimedJob> claimForExecution(UUID jobId, String workerId,
                                                   Instant now, Instant leaseUntil) {
        List<ClaimedJob> claimed = jdbcTemplate.query("""
                UPDATE jobs
                SET status = 'RUNNING',
                    worker_id = ?,
                    lease_until = ?,
                    started_at = COALESCE(started_at, ?),
                    attempt_count = attempt_count + 1,
                    updated_at = ?
                WHERE id = ?
                  AND (
                    status = 'QUEUED'
                    OR (status = 'RUNNING' AND (lease_until IS NULL OR lease_until <= ?))
                    OR status = 'RETRY_WAIT'
                  )
                RETURNING id, type, payload, attempt_count, max_attempts
                """,
                claimRowMapper(), workerId, toUtc(leaseUntil),
                toUtc(now), toUtc(now), jobId, toUtc(now));
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedJob job = claimed.get(0);
        return Optional.of(startAttempt(job, workerId, now));
    }

    private ClaimedJob startAttempt(ClaimedJob job, String workerId, Instant now) {
        Long attemptRowId = jdbcTemplate.queryForObject("""
                INSERT INTO job_attempts (job_id, attempt_number, worker_id, status, started_at)
                VALUES (?, ?, ?, 'RUNNING', ?)
                RETURNING id
                """,
                Long.class, job.jobId(), job.attemptNumber(), workerId, toUtc(now));
        return new ClaimedJob(job.jobId(), workerId, job.type(), job.payload(), job.attemptNumber(),
                job.maxAttempts(), attemptRowId, now);
    }

    private RowMapper<ClaimedJob> claimRowMapper() {
        return (rs, rowNum) -> new ClaimedJob(
                rs.getObject("id", UUID.class),
                null,
                rs.getString("type"),
                rs.getString("payload"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                0L,
                null);
    }

    public List<JobAttemptRecord> findAttempts(UUID jobId) {
        return jdbcTemplate.query("""
                SELECT id, job_id, attempt_number, worker_id, status, started_at, finished_at,
                       duration_ms, error_message
                FROM job_attempts
                WHERE job_id = ?
                ORDER BY attempt_number ASC, started_at ASC
                """,
                attemptRowMapper(), jobId);
    }

    public List<JobRecord> findDueJobs(Instant now) {
        return jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + """
                         FROM jobs
                        WHERE (status = 'SCHEDULED' AND scheduled_at <= ?)
                           OR (status = 'RETRY_WAIT' AND next_retry_at <= ?)
                        ORDER BY COALESCE(scheduled_at, next_retry_at) ASC
                        """,
                jobRowMapper(), toUtc(now), toUtc(now));
    }

    public void withDispatchLock(Runnable action) {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(75431)");
        action.run();
    }

    public boolean promoteToQueued(UUID jobId, String outboxPayload, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE jobs
                SET status = 'QUEUED',
                    scheduled_at = COALESCE(scheduled_at, ?),
                    next_retry_at = COALESCE(next_retry_at, scheduled_at, ?),
                    lease_until = NULL,
                    updated_at = ?
                WHERE id = ?
                  AND (
                    (status = 'SCHEDULED' AND scheduled_at <= ?)
                    OR (status = 'RETRY_WAIT' AND next_retry_at <= ?)
                  )
                """,
                toUtc(now), toUtc(now), toUtc(now), jobId, toUtc(now), toUtc(now));
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update("""
                INSERT INTO outbox_messages (job_id, destination, payload, created_at, publish_attempts)
                VALUES (?, 'jobs:ready', ?::jsonb, ?, 0)
                """,
                jobId, outboxPayload, toUtc(now));
        return true;
    }

    private RowMapper<JobRecord> jobRowMapper() {
        return (rs, rowNum) -> new JobRecord(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("payload"),
                JobStatus.valueOf(rs.getString("status")),
                instant(rs, "scheduled_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "started_at"),
                instant(rs, "completed_at"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                instant(rs, "next_retry_at"),
                rs.getString("result"),
                rs.getString("last_error"),
                rs.getString("worker_id"),
                instant(rs, "lease_until"),
                rs.getString("idempotency_key"));
    }

    public void finishSucceeded(ClaimedJob claimed, String resultJson, Instant finishedAt) {
        long durationMs = elapsedMs(claimed, finishedAt);
        jdbcTemplate.update("""
                UPDATE job_attempts
                SET status = 'SUCCEEDED', finished_at = ?, duration_ms = ?
                WHERE id = ?
                """,
                toUtc(finishedAt), durationMs, claimed.attemptRowId());
        jdbcTemplate.update("""
                UPDATE jobs
                SET status = 'SUCCEEDED',
                    result = ?::jsonb,
                    completed_at = ?,
                    last_error = NULL,
                    next_retry_at = NULL,
                    lease_until = NULL,
                    updated_at = ?
                WHERE id = ? AND status = 'RUNNING' AND worker_id = ?
                """,
                resultJson, toUtc(finishedAt), toUtc(finishedAt),
                claimed.jobId(), claimed.workerId());
    }

    public void finishFailed(ClaimedJob claimed, String safeError, Instant finishedAt,
                             Instant nextRetryAt) {
        long durationMs = elapsedMs(claimed, finishedAt);
        jdbcTemplate.update("""
                UPDATE job_attempts
                SET status = 'FAILED', finished_at = ?, duration_ms = ?, error_message = ?
                WHERE id = ?
                """,
                toUtc(finishedAt), durationMs, safeError, claimed.attemptRowId());
        if (nextRetryAt == null) {
            jdbcTemplate.update("""
                    UPDATE jobs
                    SET status = 'DEAD_LETTERED',
                        completed_at = ?,
                        last_error = ?,
                        next_retry_at = NULL,
                        lease_until = NULL,
                        updated_at = ?
                    WHERE id = ? AND status = 'RUNNING' AND worker_id = ?
                    """,
                    toUtc(finishedAt), safeError, toUtc(finishedAt),
                    claimed.jobId(), claimed.workerId());
            jdbcTemplate.update("""
                    INSERT INTO outbox_messages (job_id, destination, payload, created_at,
                                                 publish_attempts, last_error)
                    VALUES (?, 'jobs:dead-letter', ?::jsonb, ?, 0, ?)
                    """,
                    claimed.jobId(), deadLetterPayload(claimed, safeError),
                    toUtc(finishedAt), safeError);
        } else {
            jdbcTemplate.update("""
                    UPDATE jobs
                    SET status = 'RETRY_WAIT',
                        next_retry_at = ?,
                        last_error = ?,
                        lease_until = NULL,
                        updated_at = ?
                    WHERE id = ? AND status = 'RUNNING' AND worker_id = ?
                    """,
                    toUtc(nextRetryAt), safeError, toUtc(finishedAt),
                    claimed.jobId(), claimed.workerId());
        }
    }

    public int markInterruptedAttempts(UUID jobId, String workerId) {
        return jdbcTemplate.update("""
                UPDATE job_attempts
                SET status = 'INTERRUPTED', finished_at = now(),
                    error_message = 'worker interrupted'
                WHERE job_id = ? AND worker_id = ? AND status = 'RUNNING'
                """, jobId, workerId);
    }

    public int expireLease(UUID jobId, String workerId) {
        return jdbcTemplate.update("""
                UPDATE jobs
                SET lease_until = now() - INTERVAL '1 second',
                    worker_id = NULL,
                    updated_at = now()
                WHERE id = ? AND status = 'RUNNING' AND worker_id = ?
                """, jobId, workerId);
    }

    public void reclaimInterrupted(UUID jobId, String workerId) {
        markInterruptedAttempts(jobId, workerId);
        expireLease(jobId, workerId);
    }

    private long elapsedMs(ClaimedJob claimed, Instant finishedAt) {
        return Math.max(0, finishedAt.toEpochMilli() - claimed.attemptStartedAt().toEpochMilli());
    }

    private String deadLetterPayload(ClaimedJob claimed, String safeError) {
        return "{\"jobId\":\"" + claimed.jobId() + "\",\"error\":\""
                + safeError.replace("\"", "'") + "\"}";
    }

    private RowMapper<JobAttemptRecord> attemptRowMapper() {
        return (rs, rowNum) -> new JobAttemptRecord(
                rs.getLong("id"),
                rs.getObject("job_id", UUID.class),
                rs.getInt("attempt_number"),
                rs.getString("worker_id"),
                JobAttemptStatus.valueOf(rs.getString("status")),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                (Long) rs.getObject("duration_ms"),
                rs.getString("error_message"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime toUtc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
