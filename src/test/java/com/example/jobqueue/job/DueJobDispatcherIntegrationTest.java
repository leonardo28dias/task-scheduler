package com.example.jobqueue.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles({"api", "test"})
class DueJobDispatcherIntegrationTest {

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("app.scheduling.enabled", () -> "true");
        registry.add("app.scheduling.dispatch-interval", () -> "PT1H");
        registry.add("app.scheduling.outbox-interval", () -> "PT1H");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Autowired
    private JobRepository repository;

    @Autowired
    private DueJobDispatcher dispatcher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("DELETE FROM job_attempts");
        jdbcTemplate.update("DELETE FROM outbox_messages");
        jdbcTemplate.update("DELETE FROM jobs");
        redisTemplate.delete("jobs:ready");
    }

    @Test
    void dueScheduledJobIsPromotedToQueuedWithOutbox() {
        UUID jobId = insertScheduledJob(Instant.now().minusSeconds(5));

        dispatcher.dispatchDueJobs();

        assertThat(jobStatus(jobId)).isEqualTo("QUEUED");
        assertThat(outboxCount(jobId)).isEqualTo(1);
    }

    @Test
    void futureScheduledJobIsLeftAlone() {
        UUID jobId = insertScheduledJob(Instant.now().plusSeconds(3600));

        dispatcher.dispatchDueJobs();

        assertThat(jobStatus(jobId)).isEqualTo("SCHEDULED");
        assertThat(outboxCount(jobId)).isZero();
    }

    @Test
    void onlyOneDispatchWinsWhenTwoDispatchersRace() throws Exception {
        UUID jobId = insertScheduledJob(Instant.now().minusSeconds(5));

        var first = new Thread(dispatcher::dispatchDueJobs);
        var second = new Thread(dispatcher::dispatchDueJobs);
        first.start();
        second.start();
        first.join(10_000);
        second.join(10_000);

        assertThat(jobStatus(jobId)).isEqualTo("QUEUED");
        assertThat(outboxCount(jobId)).isEqualTo(1);
    }

    private UUID insertScheduledJob(Instant scheduledAt) {
        Instant now = Instant.now();
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO jobs (id, type, payload, status, scheduled_at, created_at, updated_at,
                                  attempt_count, max_attempts)
                VALUES (?, 'NOOP', '{}'::jsonb, 'SCHEDULED', ?, ?, ?, 0, 3)
                """,
                jobId, utc(scheduledAt), utc(now), utc(now));
        return jobId;
    }

    private String jobStatus(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM jobs WHERE id = ?", String.class, jobId);
    }

    private int outboxCount(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE job_id = ?", Integer.class, jobId);
    }

    private java.time.OffsetDateTime utc(Instant instant) {
        return java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }
}
