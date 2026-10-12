package com.example.jobqueue.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.jobqueue.job.IdempotencyConflictException;
import com.example.jobqueue.job.JobNotFoundException;
import com.example.jobqueue.job.JobRecord;
import com.example.jobqueue.job.JobService;
import com.example.jobqueue.job.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = JobController.class)
class JobControllerTest {

    private static final String IMMEDIATE_JOB_BODY = """
            {"type":"NOOP","payload":{},"maxAttempts":3}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobService jobService;

    @Test
    void submitReturnsAcceptedWithStatusUrl() throws Exception {
        JobRecord job = jobRecord(JobStatus.QUEUED);
        when(jobService.submit(any(CreateJobRequest.class), any())).thenReturn(job);

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IMMEDIATE_JOB_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/jobs/" + job.id()))
                .andExpect(jsonPath("$.jobId").value(job.id().toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.statusUrl").value("/api/v1/jobs/" + job.id()))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void submitPassesIdempotencyKeyToService() throws Exception {
        JobRecord job = jobRecord(JobStatus.QUEUED);
        when(jobService.submit(any(CreateJobRequest.class), eq("key-1"))).thenReturn(job);

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-1")
                        .content(IMMEDIATE_JOB_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(job.id().toString()));

        verify(jobService).submit(any(CreateJobRequest.class), eq("key-1"));
    }

    @Test
    void submitRejectsUnsupportedTaskType() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"RUN_SHELL","payload":{},"maxAttempts":3}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("type")));
        verifyNoInteractions(jobService);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 11})
    void submitRejectsInvalidMaxAttempts(int maxAttempts) throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"NOOP","payload":{},"maxAttempts":%d}
                                """.formatted(maxAttempts)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(containsString("maxAttempts")));
        verifyNoInteractions(jobService);
    }

    @Test
    void submitRejectsMissingPayload() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"NOOP","maxAttempts":3}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("payload")));
        verifyNoInteractions(jobService);
    }

    @Test
    void submitRejectsNonObjectPayload() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"NOOP","payload":"not-an-object"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        verifyNoInteractions(jobService);
    }

    @Test
    void submitReturnsConflictForReusedKeyWithDifferentRequest() throws Exception {
        when(jobService.submit(any(CreateJobRequest.class), eq("key-1")))
                .thenThrow(new IdempotencyConflictException("Idempotency-Key was already used"));

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-1")
                        .content("""
                                {"type":"NOOP","payload":{"other":true},"maxAttempts":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_conflict"));
    }

    @Test
    void getJobReturnsDetails() throws Exception {
        JobDetailsResponse details = jobDetails(JobStatus.QUEUED);
        when(jobService.getJobDetails(details.jobId())).thenReturn(details);

        mockMvc.perform(get("/api/v1/jobs/" + details.jobId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(details.jobId().toString()))
                .andExpect(jsonPath("$.type").value("NOOP"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.result.output").value("ok"));
    }

    @Test
    void getJobReturns404WhenJobIsMissing() throws Exception {
        UUID missing = UUID.randomUUID();
        when(jobService.getJobDetails(missing)).thenThrow(new JobNotFoundException(missing));

        mockMvc.perform(get("/api/v1/jobs/" + missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job_not_found"));
    }

    @Test
    void getAttemptsReturnsChronologicalHistory() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(jobService.getAttempts(jobId)).thenReturn(List.of(
                new JobAttemptResponse(1L, 1, "worker-1", "SUCCEEDED",
                        Instant.now(), Instant.now(), 12L, null),
                new JobAttemptResponse(2L, 2, "worker-1", "FAILED",
                        Instant.now(), Instant.now(), 34L, "boom")));

        mockMvc.perform(get("/api/v1/jobs/" + jobId + "/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[1].attemptNumber").value(2))
                .andExpect(jsonPath("$[1].errorMessage").value("boom"));
    }

    @Test
    void getAttemptsReturns404WhenJobIsMissing() throws Exception {
        UUID missing = UUID.randomUUID();
        when(jobService.getAttempts(missing)).thenThrow(new JobNotFoundException(missing));

        mockMvc.perform(get("/api/v1/jobs/" + missing + "/attempts"))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitRejectsBlankTaskType() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"  ","payload":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
        verifyNoInteractions(jobService);
    }

    private JobRecord jobRecord(JobStatus status) {
        Instant now = Instant.now();
        return new JobRecord(UUID.randomUUID(), "NOOP", "{}", status, null,
                now, now, null, null, 0, 3, null, null, null, null, null, null);
    }

    private JobDetailsResponse jobDetails(JobStatus status) {
        Instant now = Instant.now();
        return new JobDetailsResponse(UUID.randomUUID(), "NOOP", status.name(), now, null,
                0, 3, null, Map.of("output", "ok"), null, null, null);
    }
}
