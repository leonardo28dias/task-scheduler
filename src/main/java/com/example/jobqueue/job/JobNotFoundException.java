package com.example.jobqueue.job;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

    private final UUID jobId;

    public JobNotFoundException(UUID jobId) {
        super("Job not found: " + jobId);
        this.jobId = jobId;
    }

    public UUID jobId() {
        return jobId;
    }
}
