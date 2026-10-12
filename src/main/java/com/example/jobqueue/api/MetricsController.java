package com.example.jobqueue.api;

import com.example.jobqueue.job.JobMetrics;
import com.example.jobqueue.job.JobMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {

    private final JobMetricsService metricsService;

    public MetricsController(JobMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/api/v1/metrics")
    public JobMetrics getMetrics() {
        return metricsService.collect();
    }
}
