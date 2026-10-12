package com.example.jobqueue.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.example.jobqueue.api.CreateJobRequest;
import com.example.jobqueue.api.JobAttemptResponse;
import com.example.jobqueue.api.JobDetailsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class JobServiceTest {

    private final JobRepository repository = mock(JobRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private JobService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new JobService(repository, transactionManager, objectMapper);
    }

    @Test
    void immediateSubmissionIsQueuedAndWritesOutboxMessage() {
        JobRecord job = service.submit(request("NOOP", Map.of(), null, 3), null);

        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attemptCount()).isZero();
        assertThat(job.idempotencyKey()).isNull();
        verify(repository).insertJob(any(JobRecord.class));
        verify(repository).insertOutbox(
                eq(job.id()), eq(OutboxDestination.READY), contains(job.id().toString()));
    }

    @Test
    void futureDatedSubmissionIsScheduledWithoutOutboxMessage() {
        Instant future = Instant.now().plus(Duration.ofHours(1));

        JobRecord job = service.submit(
                request("GENERATE_REPORT", Map.of(), future, null), null);

        assertThat(job.status()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job.scheduledAt()).isEqualTo(future.truncatedTo(ChronoUnit.MICROS));
        verify(repository).insertJob(any(JobRecord.class));
        verify(repository, never()).insertOutbox(any(), any(), any());
    }

    @Test
    void omittedMaxAttemptsDefaultsToThree() {
        JobRecord job = service.submit(request("NOOP", Map.of(), null, null), null);

        assertThat(job.maxAttempts()).isEqualTo(3);
    }

    @Test
    void idempotentReplayReturnsOriginalJobWithoutInserting() {
        JobRecord existing = existingJob("{\"report\":\"monthly\"}", 3, null);
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        JobRecord result = service.submit(
                request("NOOP", Map.of("report", "monthly"), null, 3), "key-1");

        assertThat(result.id()).isEqualTo(existing.id());
        verify(repository, never()).insertJob(any());
    }

    @Test
    void idempotentReplayWithDifferentPayloadConflicts() {
        JobRecord existing = existingJob("{\"report\":\"weekly\"}", 3, null);
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(
                request("NOOP", Map.of("report", "monthly"), null, 3), "key-1"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void blankIdempotencyKeyIsTreatedAsAbsent() {
        service.submit(request("NOOP", Map.of(), null, 3), "   ");

        verify(repository, never()).findByIdempotencyKey(any());
        verify(repository).insertJob(any(JobRecord.class));
    }

    @Test
    void oversizedIdempotencyKeyIsRejected() {
        String key = "k".repeat(129);

        assertThatThrownBy(() -> service.submit(request("NOOP", Map.of(), null, 3), key))
                .isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).insertJob(any());
    }

    @Test
    void duplicateKeyRaceReturnsAlreadyCreatedJob() {
        JobRecord existing = existingJob("{}", 3, null);
        when(repository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        doThrow(new DuplicateKeyException("duplicate key value violates unique constraint"))
                .when(repository).insertJob(any(JobRecord.class));

        JobRecord result = service.submit(request("NOOP", Map.of(), null, 3), "key-1");

        assertThat(result.id()).isEqualTo(existing.id());
    }

    @Test
    void getJobDetailsParsesResultJson() {
        JobRecord job = existingJob("{}", 3, null);
        JobRecord withResult = new JobRecord(job.id(), job.type(), job.payload(), JobStatus.SUCCEEDED,
                null, job.createdAt(), job.createdAt(), job.createdAt(), Instant.now(),
                1, 3, null, "{\"path\":\"/reports/r1.pdf\"}", null, "worker-1", null, null);
        when(repository.findById(withResult.id())).thenReturn(Optional.of(withResult));

        JobDetailsResponse details = service.getJobDetails(withResult.id());

        assertThat(details.status()).isEqualTo("SUCCEEDED");
        assertThat(details.result()).containsEntry("path", "/reports/r1.pdf");
        assertThat(details.workerId()).isEqualTo("worker-1");
    }

    @Test
    void getJobDetailsThrowsWhenJobIsMissing() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getJobDetails(missing))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void getAttemptsReturnsMappedHistoryInOrder() {
        JobRecord job = existingJob("{}", 3, null);
        when(repository.findById(job.id())).thenReturn(Optional.of(job));
        JobAttemptRecord first = new JobAttemptRecord(10L, job.id(), 1, "worker-1",
                JobAttemptStatus.FAILED, Instant.now(), Instant.now(), 5L, "boom");
        JobAttemptRecord second = new JobAttemptRecord(11L, job.id(), 2, "worker-1",
                JobAttemptStatus.SUCCEEDED, Instant.now(), Instant.now(), 7L, null);
        when(repository.findAttempts(job.id())).thenReturn(List.of(first, second));

        List<JobAttemptResponse> attempts = service.getAttempts(job.id());

        assertThat(attempts).extracting(JobAttemptResponse::attemptNumber).containsExactly(1, 2);
        assertThat(attempts.get(0).errorMessage()).isEqualTo("boom");
        assertThat(attempts.get(1).status()).isEqualTo("SUCCEEDED");
    }

    private CreateJobRequest request(String type, Map<String, Object> payload,
                                     Instant scheduledAt, Integer maxAttempts) {
        return new CreateJobRequest(type, payload, scheduledAt, maxAttempts);
    }

    private JobRecord existingJob(String payloadJson, int maxAttempts, Instant scheduledAt) {
        Instant now = Instant.now();
        return new JobRecord(UUID.randomUUID(), "NOOP", payloadJson, JobStatus.QUEUED, scheduledAt,
                now, now, null, null, 0, maxAttempts, null, null, null, null, null, "key-1");
    }
}
