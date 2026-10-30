package com.system.common.payload;

import com.system.common.enums.JobType;
import tools.jackson.databind.JsonNode;

/**
 * The parameters each built-in job type reads from its payload, with their ranges
 * and defaults. One place for both sides: the api checks a payload against these
 * rules at submission (FR-2), and the worker's handlers read their parameters
 * through the same methods, so the two can't disagree.
 *
 * A missing field takes its default. A field that is present but wrong (wrong type,
 * out of range, JSON null) throws InvalidPayloadException. Fields a job type doesn't
 * read are ignored: a payload may carry other data, up to the size limit.
 */
public final class JobPayloads {

    public static final long CPU_DEFAULT_ITERATIONS = 100_000;
    public static final long CPU_MAX_ITERATIONS = 100_000_000;
    public static final long IO_DEFAULT_SLEEP_MS = 1_000;
    // The spec's "a few minutes at most"
    public static final long IO_MAX_SLEEP_MS = 600_000;
    /** FLAKY with neither field set fails half the time. */
    public static final double FLAKY_DEFAULT_FAIL_PROBABILITY = 0.5;

    private JobPayloads() {
    }

    /** Checks every parameter the job type reads, as its handler will. */
    public static void validate(JobType type, JsonNode payload) {
        switch (type) {
            case CPU -> {
                cpuIterations(payload);
                cpuSeed(payload);
            }
            case IO -> ioSleepMs(payload);
            case FLAKY -> {
                flakySucceedOnAttempt(payload);
                flakyFailProbability(payload);
            }
        }
    }

    /** CPU: how many SHA-256 rounds, 1 to 100,000,000 (default 100,000). */
    public static long cpuIterations(JsonNode payload) {
        return integer(payload, "iterations", CPU_DEFAULT_ITERATIONS, 1, CPU_MAX_ITERATIONS);
    }

    /** CPU: the starting value, any integer (default 0). */
    public static long cpuSeed(JsonNode payload) {
        return integer(payload, "seed", 0, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /** IO: how long to sleep, 0 to 600,000 ms (default 1,000). */
    public static long ioSleepMs(JsonNode payload) {
        return integer(payload, "sleepMs", IO_DEFAULT_SLEEP_MS, 0, IO_MAX_SLEEP_MS);
    }

    /** FLAKY: fail every attempt before this one, 1 or more; null if not set. */
    public static Long flakySucceedOnAttempt(JsonNode payload) {
        return optionalInteger(payload, "succeedOnAttempt", 1, Integer.MAX_VALUE);
    }

    /**
     * FLAKY: chance of failing, 0 to 1. If not set: 0 when succeedOnAttempt is set
     * (so that rule alone decides), otherwise 0.5.
     */
    public static double flakyFailProbability(JsonNode payload) {
        Double requested = optionalNumber(payload, "failProbability", 0.0, 1.0);
        if (requested != null) {
            return requested;
        }
        return flakySucceedOnAttempt(payload) == null ? FLAKY_DEFAULT_FAIL_PROBABILITY : 0.0;
    }

    private static long integer(JsonNode payload, String field, long defaultValue, long min, long max) {
        Long value = optionalInteger(payload, field, min, max);
        return value == null ? defaultValue : value;
    }

    private static Long optionalInteger(JsonNode payload, String field, long min, long max) {
        JsonNode node = payload.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid(field, "must be an integer");
        }
        long value = node.asLong();
        if (value < min || value > max) {
            throw invalid(field, "must be between " + min + " and " + max);
        }
        return value;
    }

    private static Double optionalNumber(JsonNode payload, String field, double min, double max) {
        JsonNode node = payload.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isNumber()) {
            throw invalid(field, "must be a number");
        }
        double value = node.asDouble();
        if (value < min || value > max) {
            throw invalid(field, "must be between " + min + " and " + max);
        }
        return value;
    }

    private static InvalidPayloadException invalid(String field, String problem) {
        return new InvalidPayloadException("Invalid payload: " + field + " " + problem);
    }
}
