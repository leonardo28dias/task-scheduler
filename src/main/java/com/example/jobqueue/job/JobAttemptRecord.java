package com.example.jobqueue.job;

import java.time.Instant;
import java.util.UUID;

public record JobAttemptRecord(
        long id,
        UUID jobId,
        int attemptNumber,
        String workerId,
        JobAttemptStatus status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String errorMessage) {
}
