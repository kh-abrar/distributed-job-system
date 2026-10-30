package com.system.worker.handler;

import com.system.common.enums.JobType;
import com.system.common.payload.InvalidPayloadException;
import com.system.common.payload.JobPayloads;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Waits without using CPU, for concurrency and throughput experiments (spec 1.4).
 *
 * Payload: {"sleepMs": 0..600000 (default 1000)}. The cap matches the spec's
 * "a few minutes at most". Result: {"sleptMs": n}.
 */
@Component
public class IoHandler implements JobHandler {

    @Override
    public JobType type() {
        return JobType.IO;
    }

    @Override
    public JsonNode handle(JobContext context) throws JobFailedException, InterruptedException {
        long sleepMs;
        try {
            sleepMs = JobPayloads.ioSleepMs(context.payload());
        } catch (InvalidPayloadException e) {
            // The api rejects these at submission; this covers jobs that reached the table another way
            throw new JobFailedException(e.getMessage());
        }

        // Throws InterruptedException if the worker interrupts the run
        Thread.sleep(sleepMs);

        return JsonNodeFactory.instance.objectNode().put("sleptMs", sleepMs);
    }
}
