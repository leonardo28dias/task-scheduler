package com.example.jobqueue.worker.handlers;

import java.util.Map;

import com.example.jobqueue.job.RetryableTaskException;
import com.example.jobqueue.job.TaskResult;
import org.springframework.stereotype.Component;

@Component
public class FailOnceTaskHandler implements TaskHandler {

    @Override
    public String type() {
        return "FAIL_ONCE";
    }

    @Override
    public TaskResult execute(Map<String, Object> payload) {
        Object attempt = payload.get("attempt");
        int attemptNumber = attempt == null ? 1 : Integer.parseInt(attempt.toString());
        if (attemptNumber < 2) {
            throw new RetryableTaskException("Simulated first-attempt failure");
        }
        return TaskResult.of("recoveredOnAttempt", attemptNumber);
    }
}
