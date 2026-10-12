package com.example.jobqueue.api;

public record ApiError(int status, String error, String message) {
}
