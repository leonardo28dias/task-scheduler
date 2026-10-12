package com.example.jobqueue.worker.handlers;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.jobqueue.job.InvalidRequestException;
import org.springframework.stereotype.Component;

@Component
public class TaskHandlerRegistry {

    private final Map<String, TaskHandler> handlers;

    public TaskHandlerRegistry(List<TaskHandler> handlers) {
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(TaskHandler::type, Function.identity()));
    }

    public TaskHandler handlerFor(String type) {
        TaskHandler handler = handlers.get(type);
        if (handler == null) {
            throw new InvalidRequestException("No handler registered for task type: " + type);
        }
        return handler;
    }
}
