package com.example.jobqueue.api;

import java.time.Instant;
import java.util.Map;

import com.example.jobqueue.job.SupportedTaskType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateJobRequest(
        @NotBlank(message = "type must not be blank") @SupportedTaskType String type,
        @NotNull(message = "payload must be a JSON object") Map<String, Object> payload,
        Instant scheduledAt,
        @Min(value = 1, message = "maxAttempts must be at least 1")
        @Max(value = 10, message = "maxAttempts must be at most 10")
        Integer maxAttempts) {
}
