package com.example.jobqueue.job;

import java.time.Duration;

public class BackoffPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;

    public BackoffPolicy(Duration baseDelay, Duration maxDelay) {
        if (baseDelay == null || baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay == null || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be at least baseDelay");
        }
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    public Duration delayForAttempt(int attemptNumber) {
        long multiplier = 1L << Math.min(attemptNumber - 1, 30);
        Duration delay = baseDelay.multipliedBy(multiplier);
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
