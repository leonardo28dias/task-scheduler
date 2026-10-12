package com.example.jobqueue.worker.handlers;

import java.util.Map;

import com.example.jobqueue.job.TaskResult;
import org.springframework.stereotype.Component;

@Component
public class NoopTaskHandler implements TaskHandler {

    @Override
    public String type() {
        return "NOOP";
    }

    @Override
    public TaskResult execute(Map<String, Object> payload) {
        return TaskResult.of("ok", true);
    }
}
