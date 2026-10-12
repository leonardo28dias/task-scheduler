package com.example.jobqueue.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobApiIntegrationTest {

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
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private JobRepository jobRepository;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM job_attempts");
        jdbcTemplate.update("DELETE FROM outbox_messages");
        jdbcTemplate.update("DELETE FROM jobs");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void immediateSubmissionIsQueuedWithTransactionalOutboxMessage() throws Exception {
        MvcResult result = submitJob("""
                {"type":"NOOP","payload":{},"maxAttempts":3}
                """, null);
        UUID jobId = jobIdOf(result);

        mockMvc.perform(get("/api/v1/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.maxAttempts").value(3));

        assertThat(outboxCount(jobId)).isEqualTo(1);
        String destination = jdbcTemplate.queryForObject(
                "SELECT destination FROM outbox_messages WHERE job_id = ?", String.class, jobId);
        assertThat(destination).isEqualTo("jobs:ready");

        mockMvc.perform(get("/api/v1/jobs/" + jobId + "/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void futureDatedSubmissionIsScheduledAndHasNoOutboxMessage() throws Exception {
        Instant future = Instant.now().plus(Duration.ofHours(2));
        MvcResult result = submitJob("""
                {"type":"GENERATE_REPORT","payload":{"period":"2026-Q4"},"scheduledAt":"%s"}
                """.formatted(future), null);
        UUID jobId = jobIdOf(result);

        mockMvc.perform(get("/api/v1/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.scheduledAt").exists())
                .andExpect(jsonPath("$.type").value("GENERATE_REPORT"));

        assertThat(outboxCount(jobId)).isZero();
        Integer totalOutbox = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages", Integer.class);
        assertThat(totalOutbox).isZero();
    }

    private MvcResult submitJob(String body, String idempotencyKey) throws Exception {
        var request = post("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn();
    }

    private UUID jobIdOf(MvcResult result) throws IOException {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("jobId").asText());
    }

    private int outboxCount(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE job_id = ?", Integer.class, jobId);
    }

    @Test
    void repeatedSubmissionWithSameKeyReturnsSameJob() throws Exception {
        String body = """
                {"type":"SIMULATE_EMAIL","payload":{"to":"a@b.c"},"maxAttempts":3}
                """;
        UUID first = jobIdOf(submitJob(body, "idem-1"));
        UUID second = jobIdOf(submitJob(body, "idem-1"));

        assertThat(second).isEqualTo(first);
        Integer jobCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM jobs WHERE idempotency_key = 'idem-1'", Integer.class);
        assertThat(jobCount).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentRequestReturnsConflict() throws Exception {
        submitJob("""
                {"type":"NOOP","payload":{"a":1},"maxAttempts":3}
                """, "idem-2");

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-2")
                        .content("""
                                {"type":"NOOP","payload":{"a":2},"maxAttempts":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_conflict"));

        Integer jobCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM jobs WHERE idempotency_key = 'idem-2'", Integer.class);
        assertThat(jobCount).isEqualTo(1);
    }

    @Test
    void concurrentSubmissionsWithSameKeyCreateSingleJob() throws Exception {
        String body = """
                {"type":"NOOP","payload":{"concurrent":true},"maxAttempts":3}
                """;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<UUID> firstFuture = pool.submit(() -> {
                start.await();
                return jobIdOf(performConcurrentSubmit(body));
            });
            Future<UUID> secondFuture = pool.submit(() -> {
                start.await();
                return jobIdOf(performConcurrentSubmit(body));
            });
            start.countDown();

            UUID first = firstFuture.get(10, TimeUnit.SECONDS);
            UUID second = secondFuture.get(10, TimeUnit.SECONDS);
            assertThat(second).isEqualTo(first);
        } finally {
            pool.shutdownNow();
        }

        Integer jobCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM jobs WHERE idempotency_key = 'concurrent-key'", Integer.class);
        assertThat(jobCount).isEqualTo(1);
    }

    private MvcResult performConcurrentSubmit(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "concurrent-key")
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
    }

    @Test
    void outboxInsertFailureRollsBackJobCreation() {
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(jobRepository).insertOutbox(any(), any(), any());

        try {
            MvcResult result = mockMvc.perform(post("/api/v1/jobs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"type":"NOOP","payload":{},"maxAttempts":3}
                                    """))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        } catch (Exception expected) {
            // the failing outbox insert also surfaces as a propagated exception
        }

        Integer jobCount = jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class);
        Integer outbox = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages", Integer.class);
        assertThat(jobCount).isZero();
        assertThat(outbox).isZero();
    }

    @Test
    void unknownJobReturns404ForDetailsAndAttempts() throws Exception {
        UUID missing = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/jobs/" + missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job_not_found"));
        mockMvc.perform(get("/api/v1/jobs/" + missing + "/attempts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job_not_found"));
    }

    @Test
    void unsupportedTaskTypeIsRejectedWithoutCreatingJob() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"DELETE_ALL_DATA","payload":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));

        Integer jobCount = jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class);
        assertThat(jobCount).isZero();
    }

    @Test
    void attemptHistoryIsReturnedInChronologicalOrder() throws Exception {
        UUID jobId = UUID.randomUUID();
        insertQueuedJob(jobId);
        insertAttempt(jobId, 2, "SUCCEEDED");
        insertAttempt(jobId, 1, "FAILED");

        mockMvc.perform(get("/api/v1/jobs/" + jobId + "/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].workerId").value("worker-1"))
                .andExpect(jsonPath("$[1].attemptNumber").value(2))
                .andExpect(jsonPath("$[1].status").value("SUCCEEDED"));
    }

    private void insertQueuedJob(UUID jobId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO jobs (id, type, payload, status, created_at, updated_at,
                                  attempt_count, max_attempts)
                VALUES (?, 'NOOP', '{}'::jsonb, 'QUEUED', ?, ?, 0, 3)
                """, jobId, utc(now), utc(now));
    }

    private void insertAttempt(UUID jobId, int attemptNumber, String status) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO job_attempts (job_id, attempt_number, worker_id, status, started_at,
                                          finished_at, duration_ms, error_message)
                VALUES (?, ?, 'worker-1', ?, ?, ?, ?, ?)
                """,
                jobId, attemptNumber, status, utc(now), utc(now), 10L,
                "FAILED".equals(status) ? "simulated failure" : null);
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
