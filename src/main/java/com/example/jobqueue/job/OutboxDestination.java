package com.example.jobqueue.job;

public enum OutboxDestination {

    READY("jobs:ready"),
    DEAD_LETTER("jobs:dead-letter");

    private final String queue;

    OutboxDestination(String queue) {
        this.queue = queue;
    }

    public String queue() {
        return queue;
    }
}
