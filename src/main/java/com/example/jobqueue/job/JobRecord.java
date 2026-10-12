package com.example.jobqueue.job;

import java.time.Instant;
import java.util.UUID;

public record JobRecord(
        UUID id,
        String type,
        String payload,
        JobStatus status,
        Instant scheduledAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        int attemptCount,
        int maxAttempts,
        Instant nextRetryAt,
        String result,
        String lastError,
        String workerId,
        Instant leaseUntil,
        String idempotencyKey) {
}
