package com.example.jobqueue.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.example.jobqueue.config.AppProperties;
import com.example.jobqueue.job.ClaimedJob;
import com.example.jobqueue.job.JobRepository;
import com.example.jobqueue.job.JobService;
import com.example.jobqueue.queue.RedisQueue;
import com.example.jobqueue.queue.RedisQueue.QueueMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
public class WorkerService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

    private final JobService jobService;
    private final JobExecutor executor;
    private final RedisQueue redisQueue;
    private final String workerId;
    private final String readyQueue;
    private final String processingQueue;
    private final String deadLetterQueue;
    private final Duration lease;
    private final Duration consumeTimeout;
    private final Duration pollInterval;
    private final boolean autostart;

    private volatile boolean running = false;
    private Thread workerThread;

    public WorkerService(JobService jobService,
                         JobExecutor executor,
                         RedisQueue redisQueue,
                         AppProperties properties) {
        this.jobService = jobService;
        this.executor = executor;
        this.redisQueue = redisQueue;
        this.workerId = resolveWorkerId(properties);
        this.readyQueue = properties.queue().ready();
        this.processingQueue = properties.queue().processingPrefix() + workerId;
        this.deadLetterQueue = properties.queue().deadLetter();
        this.lease = properties.worker().lease();
        this.consumeTimeout = properties.queue().consumeTimeout();
        this.pollInterval = properties.worker().pollInterval();
        this.autostart = properties.worker().autostart();
    }

    public static String resolveWorkerId(AppProperties properties) {
        if (properties.worker().id() != null && !properties.worker().id().isBlank()) {
            return properties.worker().id().trim();
        }
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname.trim();
        }
        return "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String workerId() {
        return workerId;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(this::runLoop, "worker-" + workerId);
        workerThread.setDaemon(false);
        workerThread.start();
        log.info("Worker {} started (processing queue {})", workerId, processingQueue);
    }

    @Override
    public void stop() {
        running = false;
        Thread thread = workerThread;
        if (thread != null) {
            workerThread = null;
            thread.interrupt();
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autostart;
    }

    private void runLoop() {
        recoverOwnProcessingQueue();
        while (isRunning()) {
            try {
                QueueMessage message = redisQueue.takeNext(readyQueue, processingQueue, consumeTimeout);
                if (message == null) {
                    continue;
                }
                executor.executeOnce(workerId, message.jobId(), message,
                        redisQueue, processingQueue, lease);
            } catch (RuntimeException e) {
                if (!isRunning()) {
                    break;
                }
                log.warn("Worker {} loop error, backing off", workerId, e);
                sleepQuietly(pollInterval);
            }
        }
    }

    void recoverOwnProcessingQueue() {
        while (true) {
            QueueMessage message = redisQueue.moveOneNow(processingQueue, readyQueue);
            if (message == null) {
                return;
            }
            jobService.reclaimInterrupted(message.jobId(), workerId);
            log.info("Worker {} recovered unfinished job {}", workerId, message.jobId());
        }
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
