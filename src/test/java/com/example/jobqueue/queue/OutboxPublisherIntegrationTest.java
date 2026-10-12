package com.example.jobqueue.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.jobqueue.job.JobRecord;
import com.example.jobqueue.job.JobRepository;
import com.example.jobqueue.job.JobStatus;
import com.example.jobqueue.job.OutboxDestination;
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
class OutboxPublisherIntegrationTest {

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
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private RedisQueue redisQueue;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("DELETE FROM job_attempts");
        jdbcTemplate.update("DELETE FROM outbox_messages");
        jdbcTemplate.update("DELETE FROM jobs");
        redisTemplate.delete("jobs:ready");
        redisTemplate.delete("test-drain");
    }

    @Test
    void unpublishedOutboxRowsArePublishedAndMarked() {
        UUID jobId = insertQueuedJob();
        repository.insertOutbox(jobId, OutboxDestination.READY, queuePayload(jobId));

        publisher.publishPending();

        assertThat(redisQueue.queueSize("jobs:ready")).isEqualTo(1);
        RedisQueue.QueueMessage message = redisQueue.moveOneNow("jobs:ready", "test-drain");
        assertThat(message.jobId()).isEqualTo(jobId);
        assertThat(unpublishedCount()).isZero();
    }

    @Test
    void duplicatedOutboxRowsPublishTwiceAndStaySafe() {
        UUID jobId = insertQueuedJob();
        repository.insertOutbox(jobId, OutboxDestination.READY, queuePayload(jobId));
        repository.insertOutbox(jobId, OutboxDestination.READY, queuePayload(jobId));

        publisher.publishPending();

        assertThat(redisQueue.queueSize("jobs:ready")).isEqualTo(2);
        assertThat(unpublishedCount()).isZero();
    }

    @Test
    void publishErrorIsRecordedAndTheRowCanBeRetried() {
        UUID jobId = insertQueuedJob();
        repository.insertOutbox(jobId, OutboxDestination.READY, queuePayload(jobId));

        List<OutboxRepository.OutboxMessage> claimed =
                outboxRepository.claimUnpublished("api-test", 10);
        assertThat(claimed).hasSize(1);
        outboxRepository.recordPublishError(claimed.get(0).id(), "redis unavailable");

        assertThat(unpublishedCount()).isEqualTo(1);
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT publish_attempts FROM outbox_messages", Integer.class);
        assertThat(attempts).isEqualTo(1);

        List<OutboxRepository.OutboxMessage> reclaimed =
                outboxRepository.claimUnpublished("api-test", 10);
        assertThat(reclaimed).hasSize(1);
        outboxRepository.markPublished(reclaimed.get(0).id());

        assertThat(unpublishedCount()).isZero();
    }

    private UUID insertQueuedJob() {
        Instant now = Instant.now();
        JobRecord job = new JobRecord(UUID.randomUUID(), "NOOP", "{}", JobStatus.QUEUED,
                null, now, now, null, null, 0, 3, null, null, null, null, null, null);
        repository.insertJob(job);
        return job.id();
    }

    private String queuePayload(UUID jobId) {
        return "{\"jobId\":\"" + jobId + "\",\"type\":\"NOOP\"}";
    }

    private int unpublishedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE published_at IS NULL", Integer.class);
    }
}
