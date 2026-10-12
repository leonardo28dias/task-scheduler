package com.example.jobqueue.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisQueue {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisQueue(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public QueueMessage takeNext(String readyQueue, String processingQueue, Duration timeout) {
        String raw = redis.opsForList().rightPopAndLeftPush(readyQueue, processingQueue, timeout);
        return raw == null ? null : decode(raw);
    }

    public QueueMessage moveOneNow(String sourceQueue, String destinationQueue) {
        String raw = redis.execute(connection -> {
            byte[] moved = connection.listCommands()
                    .rPopLPush(sourceQueue.getBytes(), destinationQueue.getBytes());
            return moved == null ? null : new String(moved);
        }, true);
        return raw == null ? null : decode(raw);
    }

    public void push(String queue, QueueMessage message) {
        redis.opsForList().leftPush(queue, encode(message));
    }

    public void acknowledge(String processingQueue, QueueMessage message) {
        redis.opsForList().remove(processingQueue, 1, encode(message));
    }

    public List<QueueMessage> peekProcessing(String processingQueue) {
        List<String> raw = redis.opsForList().range(processingQueue, 0, -1);
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(this::decode).toList();
    }

    public long queueSize(String queue) {
        Long size = redis.opsForList().size(queue);
        return size == null ? 0 : size;
    }

    public void clearForTests(String queue) {
        redis.delete(queue);
    }

    private String encode(QueueMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize queue message", e);
        }
    }

    private QueueMessage decode(String raw) {
        try {
            return objectMapper.readValue(raw, QueueMessage.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot deserialize queue message: " + raw, e);
        }
    }

    public record QueueMessage(UUID messageId, UUID jobId, Instant enqueuedAt) {
    }
}
