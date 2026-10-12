package com.example.jobqueue.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30));

    @ParameterizedTest
    @CsvSource({"1, PT1S", "2, PT2S", "3, PT4S", "4, PT8S", "5, PT16S", "6, PT30S", "10, PT30S"})
    void delayDoublesUntilMaximum(int attemptNumber, String expected) {
        assertThat(policy.delayForAttempt(attemptNumber)).isEqualTo(Duration.parse(expected));
    }

    @Test
    void rejectsNonPositiveBaseDelay() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMaxDelayBelowBaseDelay() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
