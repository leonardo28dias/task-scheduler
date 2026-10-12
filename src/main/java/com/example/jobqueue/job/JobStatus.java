package com.example.jobqueue.job;

public enum JobStatus {
    SCHEDULED,
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    DEAD_LETTERED
}
