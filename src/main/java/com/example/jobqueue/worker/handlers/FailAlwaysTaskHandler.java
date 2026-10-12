package com.example.jobqueue.worker.handlers;

import java.util.Map;

import com.example.jobqueue.job.RetryableTaskException;
import com.example.jobqueue.job.TaskResult;
import org.springframework.stereotype.Component;

@Component
public class FailAlwaysTaskHandler implements TaskHandler {

    @Override
    public String type() {
        return "FAIL_ALWAYS";
    }

    @Override
    public TaskResult execute(Map<String, Object> payload) {
        throw new RetryableTaskException("Simulated permanent failure");
    }
}
