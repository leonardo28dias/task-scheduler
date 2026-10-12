package com.example.jobqueue.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.example.jobqueue.config.AppProperties;
import com.example.jobqueue.job.BackoffPolicy;
import com.example.jobqueue.job.ClaimedJob;
import com.example.jobqueue.job.InvalidRequestException;
import com.example.jobqueue.job.JobRepository;
import com.example.jobqueue.job.JobService;
import com.example.jobqueue.job.RetryableTaskException;
import com.example.jobqueue.job.TaskResult;
import com.example.jobqueue.queue.RedisQueue;
import com.example.jobqueue.worker.handlers.TaskHandlerRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
public class JobExecutor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {
            };

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final JobRepository repository;
    private final JobService jobService;
    private final TaskHandlerRegistry handlers;
    private final ObjectMapper objectMapper;
    private final BackoffPolicy backoffPolicy;

    public JobExecutor(JobRepository repository,
                       JobService jobService,
                       TaskHandlerRegistry handlers,
                       ObjectMapper objectMapper,
                       AppProperties properties) {
        this.repository = repository;
        this.jobService = jobService;
        this.handlers = handlers;
        this.objectMapper = objectMapper;
        this.backoffPolicy = new BackoffPolicy(
                properties.retry().baseDelay(), properties.retry().maxDelay());
    }

    public boolean executeOnce(String workerId, UUID jobId, RedisQueue.QueueMessage message,
                               RedisQueue redisQueue, String processingQueue, Duration lease) {
        Optional<ClaimedJob> claimed = jobService.claimForExecution(
                jobId, workerId, Instant.now().plus(lease));
        if (claimed.isEmpty()) {
            acknowledgeDuplicate(redisQueue, processingQueue, message);
            return false;
        }
        boolean completed = runAttempt(workerId, claimed.get());
        if (completed) {
            acknowledgeDuplicate(redisQueue, processingQueue, message);
        }
        return completed;
    }

    private void acknowledgeDuplicate(RedisQueue redisQueue, String processingQueue,
                                      RedisQueue.QueueMessage message) {
        try {
            redisQueue.acknowledge(processingQueue, message);
        } catch (RuntimeException e) {
            log.warn("Failed to acknowledge queue message for job {}", message.jobId(), e);
        }
    }

    private boolean runAttempt(String workerId, ClaimedJob claimed) {
        Map<String, Object> payload = payloadWithAttempt(claimed);
        try {
            String type = claimed.type();
            if ("NOOP".equals(type) || "SIMULATE_EMAIL".equals(type) || "GENERATE_REPORT".equals(type)) {
                jobService.finishSucceeded(claimed, toJson(Map.of("ok", true, "type", type)));
                return true;
            }
            TaskResult result = handlers.handlerFor(type).execute(payload);
            jobService.finishSucceeded(claimed, toJson(result.data()));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jobService.finishFailed(claimed, "interrupted during execution", retryAt(claimed));
            return false;
        } catch (RetryableTaskException e) {
            jobService.finishFailed(claimed, shortError(e.getMessage()), retryAt(claimed));
            return false;
        } catch (InvalidRequestException e) {
            jobService.finishFailed(claimed, shortError(e.getMessage()), retryAt(claimed));
            return false;
        } catch (Exception e) {
            jobService.finishFailed(claimed, shortError(e.getMessage()), retryAt(claimed));
            return false;
        }
    }

    private Instant retryAt(ClaimedJob claimed) {
        int maxAttempts = Math.max(claimed.maxAttempts(), 1);
        if (claimed.attemptNumber() >= maxAttempts) {
            return null;
        }
        int boundedAttempt = Math.min(Math.max(claimed.attemptNumber(), 1), maxAttempts);
        return Instant.now().plus(backoffPolicy.delayForAttempt(boundedAttempt));
    }

    private Map<String, Object> payloadWithAttempt(ClaimedJob claimed) {
        Map<String, Object> payload = new HashMap<>(parsePayload(claimed.payload()));
        payload.put("attempt", claimed.attemptNumber());
        return payload;
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new InvalidRequestException("Job payload is not valid JSON");
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Result cannot be serialized to JSON", e);
        }
    }

    private String shortError(String message) {
        String text = message == null ? "task failed" : message;
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
