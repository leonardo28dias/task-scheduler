package com.example.jobqueue.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Queue queue = new Queue();
    private final Worker worker = new Worker();
    private final Outbox outbox = new Outbox();
    private final Scheduling scheduling = new Scheduling();
    private final Retry retry = new Retry();

    public Queue queue() {
        return queue;
    }

    public Worker worker() {
        return worker;
    }

    public Outbox outbox() {
        return outbox;
    }

    public Scheduling scheduling() {
        return scheduling;
    }

    public Retry retry() {
        return retry;
    }

    // JavaBean accessors required so Spring Boot's @ConfigurationProperties binder
    // (which uses java.beans.Introspector) can discover and bind the nested
    // app.queue.* / app.worker.* / app.outbox.* / app.scheduling.* / app.retry.* trees.
    public Queue getQueue() {
        return queue;
    }

    public Worker getWorker() {
        return worker;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public Scheduling getScheduling() {
        return scheduling;
    }

    public Retry getRetry() {
        return retry;
    }

    public static class Queue {
        private String ready = "jobs:ready";
        private String processingPrefix = "jobs:processing:";
        private String deadLetter = "jobs:dead-letter";
        private Duration consumeTimeout = Duration.ofSeconds(5);

        public String ready() {
            return ready;
        }

        public void setReady(String ready) {
            this.ready = ready;
        }

        public String getReady() {
            return ready;
        }

        public String processingPrefix() {
            return processingPrefix;
        }

        public void setProcessingPrefix(String processingPrefix) {
            this.processingPrefix = processingPrefix;
        }

        public String getProcessingPrefix() {
            return processingPrefix;
        }

        public String deadLetter() {
            return deadLetter;
        }

        public void setDeadLetter(String deadLetter) {
            this.deadLetter = deadLetter;
        }

        public String getDeadLetter() {
            return deadLetter;
        }

        public Duration consumeTimeout() {
            return consumeTimeout;
        }

        public void setConsumeTimeout(Duration consumeTimeout) {
            this.consumeTimeout = consumeTimeout;
        }

        public Duration getConsumeTimeout() {
            return consumeTimeout;
        }
    }

    public static class Worker {
        private String id;
        private Duration lease = Duration.ofMinutes(5);
        private Duration pollInterval = Duration.ofMillis(200);
        private boolean autostart = true;

        public String id() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public Duration lease() {
            return lease;
        }

        public void setLease(Duration lease) {
            this.lease = lease;
        }

        public Duration getLease() {
            return lease;
        }

        public Duration pollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public boolean autostart() {
            return autostart;
        }

        public void setAutostart(boolean autostart) {
            this.autostart = autostart;
        }

        public boolean isAutostart() {
            return autostart;
        }
    }

    public static class Outbox {
        private int batchSize = 50;

        public int batchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getBatchSize() {
            return batchSize;
        }
    }

    public static class Scheduling {
        private boolean enabled = true;
        private Duration outboxInterval = Duration.ofSeconds(2);
        private Duration dispatchInterval = Duration.ofSeconds(2);

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Duration outboxInterval() {
            return outboxInterval;
        }

        public void setOutboxInterval(Duration outboxInterval) {
            this.outboxInterval = outboxInterval;
        }

        public Duration getOutboxInterval() {
            return outboxInterval;
        }

        public Duration dispatchInterval() {
            return dispatchInterval;
        }

        public void setDispatchInterval(Duration dispatchInterval) {
            this.dispatchInterval = dispatchInterval;
        }

        public Duration getDispatchInterval() {
            return dispatchInterval;
        }
    }

    public static class Retry {
        private Duration baseDelay = Duration.ofSeconds(1);
        private Duration maxDelay = Duration.ofSeconds(30);

        public Duration baseDelay() {
            return baseDelay;
        }

        public void setBaseDelay(Duration baseDelay) {
            this.baseDelay = baseDelay;
        }

        public Duration getBaseDelay() {
            return baseDelay;
        }

        public Duration maxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }
    }
}
