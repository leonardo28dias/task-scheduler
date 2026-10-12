package com.example.jobqueue.worker.handlers;

import java.util.Map;

import com.example.jobqueue.job.TaskResult;

public interface TaskHandler {

    String type();

    TaskResult execute(Map<String, Object> payload) throws Exception;
}
