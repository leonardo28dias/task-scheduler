package com.example.jobqueue.job;

import java.time.Instant;

public record JobMetrics(long totalJobs,
                         long queuedJobs,
                         long runningJobs,
                         long retryWaitingJobs,
                         long succeededJobs,
                         long deadLetteredJobs,
                         long totalAttempts,
                         Double averageSuccessfulDurationMs,
                         long completedLastMinute) {

    public static JobMetrics empty() {
        return new JobMetrics(0, 0, 0, 0, 0, 0, 0, null, 0);
    }
}
