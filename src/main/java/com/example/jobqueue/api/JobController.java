package com.example.jobqueue.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.example.jobqueue.job.JobRecord;
import com.example.jobqueue.job.JobService;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/api/v1/jobs")
    public ResponseEntity<JobSubmissionResponse> submitJob(
            @Valid @RequestBody CreateJobRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        JobRecord job = jobService.submit(request, idempotencyKey);
        String statusUrl = "/api/v1/jobs/" + job.id();
        JobSubmissionResponse body = new JobSubmissionResponse(
                job.id(), job.status().name(), job.createdAt(), statusUrl);
        return ResponseEntity.accepted().location(URI.create(statusUrl)).body(body);
    }

    @GetMapping("/api/v1/jobs/{jobId}")
    public JobDetailsResponse getJob(@PathVariable UUID jobId) {
        return jobService.getJobDetails(jobId);
    }

    @GetMapping("/api/v1/jobs/{jobId}/attempts")
    public List<JobAttemptResponse> getJobAttempts(@PathVariable UUID jobId) {
        return jobService.getAttempts(jobId);
    }
}
