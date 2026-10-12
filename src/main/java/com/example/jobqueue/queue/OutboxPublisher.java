package com.example.jobqueue.queue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.example.jobqueue.config.AppProperties;
import com.example.jobqueue.queue.OutboxRepository.OutboxMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("api")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final RedisQueue redisQueue;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           RedisQueue redisQueue,
                           PlatformTransactionManager transactionManager,
                           ObjectMapper objectMapper,
                           AppProperties properties) {
        this.outboxRepository = outboxRepository;
        this.redisQueue = redisQueue;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.batchSize = properties.outbox().batchSize();
    }

    @Scheduled(fixedDelayString = "${app.scheduling.outbox-interval:PT2S}")
    public void publishPending() {
        String claimOwner = "api-" + ProcessHandle.current().pid();
        List<OutboxMessage> messages = transactionTemplate.execute(status ->
                outboxRepository.claimUnpublished(claimOwner, batchSize));
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (OutboxMessage message : messages) {
            publishOne(message);
        }
    }

    private void publishOne(OutboxMessage message) {
        try {
            redisQueue.push(message.destination(), toQueueMessage(message));
        } catch (RuntimeException e) {
            log.warn("Failed to publish outbox message {}", message.id(), e);
            transactionTemplate.executeWithoutResult(status ->
                    outboxRepository.recordPublishError(message.id(), shortError(e)));
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status ->
                    outboxRepository.markPublished(message.id()));
        } catch (RuntimeException e) {
            log.warn("Published outbox message {} but failed to mark it published; "
                    + "it will be republished on the next run", message.id(), e);
        }
    }

    private RedisQueue.QueueMessage toQueueMessage(OutboxMessage message) {
        try {
            QueuePayload payload = objectMapper.readValue(message.payload(), QueuePayload.class);
            return new RedisQueue.QueueMessage(message.id(), payload.jobId(),
                    java.time.Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException("Outbox payload is not valid: " + message.payload(), e);
        }
    }

    private String shortError(RuntimeException e) {
        String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private record QueuePayload(UUID jobId, String type) {
    }
}
