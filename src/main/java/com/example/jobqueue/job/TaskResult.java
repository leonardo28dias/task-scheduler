package com.example.jobqueue.job;

import java.util.Map;

public record TaskResult(Map<String, Object> data) {

    public static TaskResult of(String key, Object value) {
        return new TaskResult(Map.of(key, value));
    }

    public static TaskResult empty() {
        return new TaskResult(Map.of());
    }
}
