package com.system.worker.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.system.common.enums.JobType;
import org.springframework.stereotype.Component;

/**
 * Waits without using CPU, for concurrency and throughput experiments (spec 1.4).
 *
 * Payload: {"sleepMs": 0..600000 (default 1000)}. The cap matches the spec's
 * "a few minutes at most". Result: {"sleptMs": n}.
 */
@Component
public class IoHandler implements JobHandler {

    private static final long DEFAULT_SLEEP_MS = 1_000;
    private static final long MAX_SLEEP_MS = 600_000;

    @Override
    public JobType type() {
        return JobType.IO;
    }

    @Override
    public JsonNode handle(JobContext context) throws JobFailedException, InterruptedException {
        long sleepMs = Payloads.integer(context.payload(), "sleepMs", DEFAULT_SLEEP_MS, 0, MAX_SLEEP_MS);

        // Throws InterruptedException if the worker interrupts the run
        Thread.sleep(sleepMs);

        return JsonNodeFactory.instance.objectNode().put("sleptMs", sleepMs);
    }
}
