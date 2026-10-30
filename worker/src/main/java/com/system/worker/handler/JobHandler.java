package com.system.worker.handler;

import com.system.common.enums.JobType;
import tools.jackson.databind.JsonNode;

/**
 * Runs one job type. Execution is at-least-once (ADR-003), so a handler must cope
 * with running the same job again; JobContext carries what it needs to notice.
 */
public interface JobHandler {

    JobType type();

    /**
     * @return the result to store on the job; must not be null
     * @throws JobFailedException   for an expected failure; the job is retried or goes DEAD
     * @throws InterruptedException if the worker interrupts the run (shutdown or lost lease)
     */
    JsonNode handle(JobContext context) throws JobFailedException, InterruptedException;
}
