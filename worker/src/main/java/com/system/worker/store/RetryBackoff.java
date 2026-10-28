package com.system.worker.store;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Retry delay after a failed attempt (FR-21, spec section 8): exponential from a
 * base, capped at a maximum, with "equal jitter".
 *
 * Equal jitter keeps half the exponential delay fixed and randomises the other
 * half. Full jitter (random between 0 and the delay) can retry almost at once,
 * which defeats the backoff; equal jitter still spreads retries out.
 */
public class RetryBackoff {

    private final long baseMillis;
    private final long maxMillis;
    private final DoubleSupplier random;

    /** @param random returns a value in [0, 1]; injectable so tests are deterministic */
    public RetryBackoff(Duration base, Duration max, DoubleSupplier random) {
        this.baseMillis = base.toMillis();
        this.maxMillis = max.toMillis();
        this.random = random;
    }

    public RetryBackoff(Duration base, Duration max) {
        this(base, max, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param attempt the attempt that just failed, starting at 1 */
    public Duration delayAfter(int attempt) {
        long ceiling = exponential(Math.max(attempt, 1));
        long half = ceiling / 2;
        return Duration.ofMillis(half + Math.round(random.getAsDouble() * (ceiling - half)));
    }

    /** base * 2^(attempt - 1), capped without overflowing for large attempt numbers. */
    private long exponential(int attempt) {
        long delay = baseMillis;
        for (int i = 1; i < attempt && delay < maxMillis; i++) {
            delay *= 2;
        }
        return Math.min(delay, maxMillis);
    }
}
