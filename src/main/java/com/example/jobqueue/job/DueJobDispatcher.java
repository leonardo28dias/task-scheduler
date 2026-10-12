package com.example.jobqueue.job;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("api")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DueJobDispatcher {

    private final JobRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public DueJobDispatcher(JobRepository repository,
                            PlatformTransactionManager transactionManager,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.scheduling.dispatch-interval:PT2S}")
    public void dispatchDueJobs() {
        Instant now = Instant.now();
        repository.withDispatchLock(() -> {
            for (JobRecord job : repository.findDueJobs(now)) {
                transactionTemplate.executeWithoutResult(status ->
                        repository.promoteToQueued(job.id(), outboxPayload(job), now));
            }
        });
    }

    private String outboxPayload(JobRecord job) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "jobId", job.id().toString(), "type", job.type()));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize outbox payload", e);
        }
    }
}
