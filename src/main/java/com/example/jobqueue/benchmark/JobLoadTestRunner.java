package com.example.jobqueue.benchmark;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JobLoadTestRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        BenchmarkResult result = new JobLoadTestRunner(config).run();
        result.print();
        if (!result.success()) {
            System.exit(1);
        }
    }

    private final Config config;
    private final HttpClient client;

    JobLoadTestRunner(Config config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    BenchmarkResult run() throws Exception {
        requireHealthyApi();
        Instant startedAt = Instant.now();
        List<UUID> jobIds = submitAll();
        PollOutcome outcome = pollUntilTerminal(jobIds, startedAt);
        Instant finishedAt = Instant.now();
        return BenchmarkResult.evaluate(config, startedAt, finishedAt, jobIds, outcome);
    }

    private void requireHealthyApi() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(config.healthUrl())
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !response.body().contains("\"status\":\"UP\"")) {
            throw new IllegalStateException("API health check failed: " + response.body());
        }
    }

    private List<UUID> submitAll() throws Exception {
        Map<Integer, UUID> idsByIndex = new ConcurrentHashMap<>();
        AtomicInteger submitted = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(config.submissionThreads())) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < config.jobCount(); i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    idsByIndex.put(index, submitOne());
                    submitted.incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.MINUTES);
            }
        }
        List<UUID> ordered = new ArrayList<>(config.jobCount());
        for (int i = 0; i < config.jobCount(); i++) {
            UUID jobId = idsByIndex.get(i);
            if (jobId == null) {
                throw new IllegalStateException("Missing job id for submission " + i);
            }
            ordered.add(jobId);
        }
        return ordered;
    }

    private UUID submitOne() {
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "type", config.jobType(),
                    "payload", Map.of(),
                    "maxAttempts", config.maxAttempts()));
            HttpRequest request = HttpRequest.newBuilder(config.jobsUrl())
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 202) {
                throw new IllegalStateException("Submission failed: " + response.statusCode()
                        + " body=" + response.body());
            }
            JsonNode node = JSON.readTree(response.body());
            return UUID.fromString(node.get("jobId").asText());
        } catch (Exception e) {
            throw new IllegalStateException("Submission failed", e);
        }
    }

    // POLL-AND-REPORT-HELPERS

    private PollOutcome pollUntilTerminal(List<UUID> jobIds, Instant startedAt) throws Exception {
        Instant deadline = startedAt.plus(config.timeout());
        int succeeded = 0;
        int deadLettered = 0;
        int failed = 0;
        List<UUID> unfinished = new ArrayList<>();
        List<UUID> inconsistent = new ArrayList<>();

        for (UUID jobId : jobIds) {
            String status = pollOne(jobId, deadline);
            if (status == null) {
                unfinished.add(jobId);
                continue;
            }
            switch (status) {
                case "SUCCEEDED" -> succeeded++;
                case "DEAD_LETTERED" -> deadLettered++;
                default -> {
                    failed++;
                    inconsistent.add(jobId);
                }
            }
        }
        return new PollOutcome(succeeded, deadLettered, failed, unfinished, inconsistent);
    }

    private String pollOne(UUID jobId, Instant deadline) throws Exception {
        while (Instant.now().isBefore(deadline)) {
            HttpRequest request = HttpRequest.newBuilder(config.jobUrl(jobId))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Status request failed for " + jobId
                        + ": " + response.statusCode());
            }
            String status = JSON.readTree(response.body()).get("status").asText();
            if ("SUCCEEDED".equals(status) || "DEAD_LETTERED".equals(status)) {
                return status;
            }
            if (!List.of("QUEUED", "RUNNING", "RETRY_WAIT", "SCHEDULED").contains(status)) {
                return status;
            }
            Thread.sleep(config.pollInterval().toMillis());
        }
        return null;
    }

    record Config(
            String baseUrl,
            int jobCount,
            int submissionThreads,
            String jobType,
            int maxAttempts,
            Duration timeout,
            Duration pollInterval) {

        static Config parse(String[] args) {
            String baseUrl = arg(args, 0, "http://localhost:8080");
            int jobCount = Integer.parseInt(arg(args, 1, "1200"));
            return new Config(baseUrl, jobCount, 16, "NOOP", 3,
                    Duration.ofMinutes(10), Duration.ofMillis(250));
        }

        private static String arg(String[] args, int index, String fallback) {
            return args.length > index ? args[index] : fallback;
        }

        URI healthUrl() {
            return URI.create(baseUrl + "/api/v1/health");
        }

        URI jobsUrl() {
            return URI.create(baseUrl + "/api/v1/jobs");
        }

        URI jobUrl(UUID jobId) {
            return URI.create(baseUrl + "/api/v1/jobs/" + jobId);
        }
    }

    record PollOutcome(
            int succeeded,
            int deadLettered,
            int failed,
            List<UUID> unfinished,
            List<UUID> inconsistent) {
    }

    record BenchmarkResult(
            int submitted,
            int succeeded,
            int deadLettered,
            int failed,
            int unfinished,
            long elapsedSeconds,
            double jobsPerMinute,
            boolean success) {

        static BenchmarkResult evaluate(Config config, Instant startedAt, Instant finishedAt,
                                        List<UUID> jobIds, PollOutcome outcome) {
            long elapsedSeconds = Math.max(1, Duration.between(startedAt, finishedAt).toSeconds());
            double jobsPerMinute = (outcome.succeeded() + outcome.deadLettered())
                    * 60.0 / elapsedSeconds;
            boolean success = outcome.unfinished().isEmpty()
                    && outcome.inconsistent().isEmpty()
                    && jobIds.size() == config.jobCount();
            return new BenchmarkResult(jobIds.size(), outcome.succeeded(), outcome.deadLettered(),
                    outcome.failed(), outcome.unfinished().size(), elapsedSeconds,
                    jobsPerMinute, success);
        }

        void print() {
            System.out.println("submitted=" + submitted);
            System.out.println("succeeded=" + succeeded);
            System.out.println("deadLettered=" + deadLettered);
            System.out.println("failed=" + failed);
            System.out.println("unfinished=" + unfinished);
            System.out.println("elapsedSeconds=" + elapsedSeconds);
            System.out.println("jobsPerMinute=" + String.format("%.1f", jobsPerMinute));
            System.out.println("success=" + success);
        }
    }
}
