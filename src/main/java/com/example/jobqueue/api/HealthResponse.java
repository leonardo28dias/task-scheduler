package com.example.jobqueue.api;

import java.time.Instant;
import java.util.Map;

public record HealthResponse(String status, Instant timestamp, Map<String, ComponentStatus> components) {
}
