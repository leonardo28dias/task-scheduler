package com.example.jobqueue.worker.handlers;

import java.util.Map;

import com.example.jobqueue.job.InvalidRequestException;
import com.example.jobqueue.job.TaskResult;
import org.springframework.stereotype.Component;

@Component
public class SimulateDelayTaskHandler implements TaskHandler {

    private static final long MAX_DELAY_SECONDS = 30;

    @Override
    public String type() {
        return "SIMULATE_DELAY";
    }

    @Override
    public TaskResult execute(Map<String, Object> payload) throws InterruptedException {
        long seconds = requestedSeconds(payload);
        Thread.sleep(seconds * 1000L);
        return new TaskResult(Map.of("delayedSeconds", seconds));
    }

    private long requestedSeconds(Map<String, Object> payload) {
        Object raw = payload.get("seconds");
        long seconds;
        try {
            seconds = raw == null ? 1L : Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("SIMULATE_DELAY payload must contain a numeric 'seconds' field");
        }
        if (seconds < 0 || seconds > MAX_DELAY_SECONDS) {
            throw new InvalidRequestException(
                    "SIMULATE_DELAY 'seconds' must be between 0 and " + MAX_DELAY_SECONDS);
        }
        return seconds;
    }
}
