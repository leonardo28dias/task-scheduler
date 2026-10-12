package com.example.jobqueue.api;

import java.time.Instant;

public record JobAttemptResponse(
        long attemptId,
        int attemptNumber,
        String workerId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String errorMessage) {
}
