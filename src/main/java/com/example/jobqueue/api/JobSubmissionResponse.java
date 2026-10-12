package com.example.jobqueue.api;

import java.time.Instant;
import java.util.UUID;

public record JobSubmissionResponse(UUID jobId, String status, Instant createdAt, String statusUrl) {
}
