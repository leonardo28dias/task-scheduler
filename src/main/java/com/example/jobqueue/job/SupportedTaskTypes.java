package com.example.jobqueue.job;

import java.util.Set;

public final class SupportedTaskTypes {

    public static final Set<String> ALL = Set.of(
            "NOOP",
            "SIMULATE_EMAIL",
            "GENERATE_REPORT",
            "SIMULATE_DELAY",
            "FAIL_ONCE",
            "FAIL_ALWAYS");

    private SupportedTaskTypes() {
    }
}
