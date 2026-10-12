package com.example.jobqueue.job;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.example.jobqueue.api.CreateJobRequest;
import com.example.jobqueue.api.JobAttemptResponse;
import com.example.jobqueue.api.JobDetailsResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobService {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final JobRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public JobService(JobRepository repository,
                      PlatformTransactionManager transactionManager,
                      ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    public JobRecord submit(CreateJobRequest request, String idempotencyKey) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (key != null) {
            Optional<JobRecord> existing = repository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return ensureSameRequest(existing.get(), request);
            }
        }
        try {
            return transactionTemplate.execute(status -> createJob(request, key));
        } catch (DuplicateKeyException e) {
            if (key == null) {
                throw e;
            }
            JobRecord existing = repository.findByIdempotencyKey(key)
                    .orElseThrow(() -> new IdempotencyConflictException(
                            "Idempotency-Key was already used with a different request"));
            return ensureSameRequest(existing, request);
        }
    }

    public JobDetailsResponse getJobDetails(UUID jobId) {
        JobRecord job = repository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        return new JobDetailsResponse(
                job.id(),
                job.type(),
                job.status().name(),
                job.createdAt(),
                job.scheduledAt(),
                job.attemptCount(),
                job.maxAttempts(),
                job.nextRetryAt(),
                parseJsonMap(job.result()),
                job.lastError(),
                job.workerId(),
                job.completedAt());
    }

    public List<JobAttemptResponse> getAttempts(UUID jobId) {
        repository.findById(jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        return repository.findAttempts(jobId).stream()
                .map(attempt -> new JobAttemptResponse(
                        attempt.id(),
                        attempt.attemptNumber(),
                        attempt.workerId(),
                        attempt.status().name(),
                        attempt.startedAt(),
                        attempt.finishedAt(),
                        attempt.durationMs(),
                        attempt.errorMessage()))
                .toList();
    }

    public Optional<ClaimedJob> claimForExecution(UUID jobId, String workerId, Instant leaseUntil) {
        return transactionTemplate.execute(status ->
                repository.claimForExecution(jobId, workerId, Instant.now(), leaseUntil));
    }

    public void reclaimInterrupted(UUID jobId, String workerId) {
        transactionTemplate.executeWithoutResult(status ->
                repository.reclaimInterrupted(jobId, workerId));
    }

    public void finishSucceeded(ClaimedJob claimed, String resultJson) {
        transactionTemplate.executeWithoutResult(status ->
                repository.finishSucceeded(claimed, resultJson, Instant.now()));
    }

    public void finishFailed(ClaimedJob claimed, String safeError, Instant nextRetryAt) {
        transactionTemplate.executeWithoutResult(status ->
                repository.finishFailed(claimed, safeError, Instant.now(), nextRetryAt));
    }

    private JobRecord createJob(CreateJobRequest request, String key) {
        Instant now = Instant.now();
        Instant scheduledAt = truncate(request.scheduledAt());
        boolean futureDated = scheduledAt != null && scheduledAt.isAfter(now);
        JobStatus status = futureDated ? JobStatus.SCHEDULED : JobStatus.QUEUED;
        int maxAttempts = request.maxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : request.maxAttempts();

        JobRecord job = new JobRecord(
                UUID.randomUUID(),
                request.type(),
                toJson(request.payload()),
                status,
                scheduledAt,
                now,
                now,
                null,
                null,
                0,
                maxAttempts,
                null,
                null,
                null,
                null,
                null,
                key);

        repository.insertJob(job);
        if (status == JobStatus.QUEUED) {
            repository.insertOutbox(job.id(), OutboxDestination.READY, outboxPayload(job));
        }
        return job;
    }

    private JobRecord ensureSameRequest(JobRecord existing, CreateJobRequest request) {
        boolean sameType = existing.type().equals(request.type());
        boolean samePayload = parseJsonMap(existing.payload()).equals(request.payload());
        boolean sameSchedule = Objects.equals(existing.scheduledAt(), truncate(request.scheduledAt()));
        int requestedMaxAttempts = request.maxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : request.maxAttempts();
        boolean sameMaxAttempts = existing.maxAttempts() == requestedMaxAttempts;

        if (sameType && samePayload && sameSchedule && sameMaxAttempts) {
            return existing;
        }
        throw new IdempotencyConflictException(
                "Idempotency-Key was already used with a different request");
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String key = idempotencyKey.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidRequestException(
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return key;
    }

    private Instant truncate(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private String outboxPayload(JobRecord job) {
        return toJson(Map.of("jobId", job.id().toString(), "type", job.type()));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payload cannot be serialized to JSON", e);
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON is not valid", e);
        }
    }
}
