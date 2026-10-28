package com.system.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Worker settings, bound from the "worker" prefix. Defaults are in application.yml
 * and follow spec section 8.
 *
 * @param enabled           runs the poll loop, heartbeats and reaper; off in most tests
 * @param id                worker id (FR-14); blank means hostname-pid-random
 * @param threads           handler threads, so at most this many jobs run at once
 * @param pollInterval      pause after a poll that found nothing to claim
 * @param claimBatchSize    most jobs one claim takes
 * @param lease             how long a claim or heartbeat keeps the job
 * @param heartbeatInterval how often running jobs' leases are renewed
 * @param reaperInterval    how often expired leases are recovered
 * @param reaperBatchSize   most jobs one reaper statement recovers
 * @param shutdownGrace     how long shutdown waits for running jobs before interrupting them
 * @param backoff           delay after a failed attempt, and after a failed poll
 */
@ConfigurationProperties(prefix = "worker")
public record WorkerProperties(
        boolean enabled,
        String id,
        int threads,
        Duration pollInterval,
        int claimBatchSize,
        Duration lease,
        Duration heartbeatInterval,
        Duration reaperInterval,
        int reaperBatchSize,
        Duration shutdownGrace,
        Backoff backoff) {

    public WorkerProperties {
        requirePositive("threads", threads);
        requirePositive("claim-batch-size", claimBatchSize);
        requirePositive("reaper-batch-size", reaperBatchSize);
        requirePositive("poll-interval", pollInterval);
        requirePositive("lease", lease);
        requirePositive("heartbeat-interval", heartbeatInterval);
        requirePositive("reaper-interval", reaperInterval);
        if (shutdownGrace == null || shutdownGrace.isNegative()) {
            throw new IllegalArgumentException("worker.shutdown-grace must be zero or more");
        }
        // Otherwise every lease would run out between two heartbeats
        if (heartbeatInterval.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("worker.heartbeat-interval must be shorter than worker.lease");
        }
        if (backoff == null) {
            throw new IllegalArgumentException("worker.backoff.base and worker.backoff.max are required");
        }
    }

    /** Retry delay: exponential from base, capped at max. */
    public record Backoff(Duration base, Duration max) {

        public Backoff {
            requirePositive("backoff.base", base);
            requirePositive("backoff.max", max);
        }
    }

    private static void requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException("worker." + name + " must be at least 1");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("worker." + name + " must be positive");
        }
    }
}
