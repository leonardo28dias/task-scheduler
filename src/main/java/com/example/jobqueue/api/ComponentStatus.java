package com.example.jobqueue.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComponentStatus(String status, String message) {

    public static ComponentStatus up() {
        return new ComponentStatus("UP", null);
    }

    public static ComponentStatus down(String message) {
        return new ComponentStatus("DOWN", message);
    }
}
