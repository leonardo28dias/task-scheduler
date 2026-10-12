package com.example.jobqueue.worker.handlers;

import java.util.HashMap;
import java.util.Map;

import com.example.jobqueue.job.TaskResult;
import org.springframework.stereotype.Component;

@Component
public class SimulateEmailTaskHandler implements TaskHandler {

    @Override
    public String type() {
        return "SIMULATE_EMAIL";
    }

    @Override
    public TaskResult execute(Map<String, Object> payload) {
        Object recipient = payload.get("to");
        Map<String, Object> data = new HashMap<>();
        data.put("simulated", true);
        data.put("recipient", recipient == null ? "unknown" : recipient.toString());
        return new TaskResult(Map.copyOf(data));
    }
}
