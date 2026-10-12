package com.example.jobqueue.job;

public class RetryableTaskException extends RuntimeException {

    public RetryableTaskException(String message) {
        super(message);
    }
}
