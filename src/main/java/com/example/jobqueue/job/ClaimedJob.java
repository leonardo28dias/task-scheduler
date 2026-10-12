package com.example.jobqueue.job;

import java.time.Instant;
import java.util.UUID;

public record ClaimedJob(
        UUID jobId,
        String workerId,
        String type,
        String payload,
        int attemptNumber,
        int maxAttempts,
        long attemptRowId,
        Instant attemptStartedAt) {
}
