package com.example.jobqueue.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record JobDetailsResponse(
        UUID jobId,
        String type,
        String status,
        Instant createdAt,
        Instant scheduledAt,
        int attemptCount,
        int maxAttempts,
        Instant nextRetryAt,
        Map<String, Object> result,
        String lastError,
        String workerId,
        Instant completedAt) {
}
