package com.system.worker.handler;

import com.system.common.enums.JobType;
import com.system.common.payload.InvalidPayloadException;
import com.system.common.payload.JobPayloads;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Fails on purpose, for retry, backoff and dead-job experiments (spec 1.4).
 *
 * Payload, either or both:
 * - "succeedOnAttempt": n (1 or more). Fails every attempt before n. Deterministic,
 *   which tests rely on. Counted within a generation, so it applies again after a requeue.
 * - "failProbability": 0..1. Fails when a random draw falls below it.
 * The attempt rule is checked first. With neither field, failProbability is 0.5;
 * with only succeedOnAttempt, it is 0.
 *
 * Result: {"attempt": n}.
 */
@Component
public class FlakyHandler implements JobHandler {

    private final DoubleSupplier random;

    public FlakyHandler() {
        this(() -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param random returns a value in [0, 1); injectable so tests are deterministic */
    FlakyHandler(DoubleSupplier random) {
        this.random = random;
    }

    @Override
    public JobType type() {
        return JobType.FLAKY;
    }

    @Override
    public JsonNode handle(JobContext context) throws JobFailedException {
        // Read everything first, so a bad payload fails the same way on every attempt
        Long succeedOnAttempt;
        double failProbability;
        try {
            succeedOnAttempt = JobPayloads.flakySucceedOnAttempt(context.payload());
            failProbability = JobPayloads.flakyFailProbability(context.payload());
        } catch (InvalidPayloadException e) {
            // The api rejects these at submission; this covers jobs that reached the table another way
            throw new JobFailedException(e.getMessage());
        }

        if (succeedOnAttempt != null && context.attempt() < succeedOnAttempt) {
            throw new JobFailedException("Planned failure on attempt " + context.attempt()
                    + " (succeeds on attempt " + succeedOnAttempt + ")");
        }
        if (random.getAsDouble() < failProbability) {
            throw new JobFailedException("Random failure (failProbability " + failProbability + ")");
        }

        return JsonNodeFactory.instance.objectNode().put("attempt", context.attempt());
    }
}
