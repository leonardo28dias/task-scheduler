package com.example.jobqueue.worker.handlers;

import java.util.Map;

import com.example.jobqueue.job.TaskResult;
import org.springframework.stereotype.Component;

@Component
public class GenerateReportTaskHandler implements TaskHandler {

    @Override
    public String type() {
        return "GENERATE_REPORT";
    }

    @Override
    public TaskResult execute(Map<String, Object> payload) {
        Object period = payload.get("period");
        String summary = "Report for period " + (period == null ? "unknown" : period.toString());
        return new TaskResult(Map.of("summary", summary, "rows", payload.size()));
    }
}
