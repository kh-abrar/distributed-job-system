package com.system.worker.store;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec section 8: exponential backoff from 2 s, capped at 60 s, with jitter.
 */
class RetryBackoffTest {

    private static final Duration BASE = Duration.ofSeconds(2);
    private static final Duration MAX = Duration.ofSeconds(60);

    private static RetryBackoff withRandom(double value) {
        return new RetryBackoff(BASE, MAX, () -> value);
    }

    @Test
    void doublesWithEachAttempt() {
        RetryBackoff top = withRandom(1.0);

        assertThat(top.delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(top.delayAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(top.delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
    }

    /** Equal jitter: never less than half the exponential delay. */
    @Test
    void jitterKeepsAtLeastHalfTheDelay() {
        assertThat(withRandom(0.0).delayAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(withRandom(0.5).delayAfter(3)).isEqualTo(Duration.ofSeconds(6));
    }

    @Test
    void isCappedAtTheMaximum() {
        assertThat(withRandom(1.0).delayAfter(10)).isEqualTo(MAX);
        assertThat(withRandom(0.0).delayAfter(10)).isEqualTo(Duration.ofSeconds(30));
    }

    /** A large attempt number must not overflow into a negative or tiny delay. */
    @Test
    void hugeAttemptNumbersStayCapped() {
        assertThat(withRandom(1.0).delayAfter(500)).isEqualTo(MAX);
    }
}
