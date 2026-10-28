package com.system.worker.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.worker.handler.HandlerRegistry;
import com.system.worker.handler.JobContext;
import com.system.worker.handler.JobFailedException;
import com.system.worker.store.ClaimedJob;
import com.system.worker.store.FailOutcome;
import com.system.worker.store.JobStore;
import com.system.worker.store.RetryBackoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one claimed job and records how it ended, through JobStore's fenced writes.
 *
 * - A result is written with complete (FR-18).
 * - JobFailedException is an expected failure (FR-20). Any other exception is a
 *   handler bug, and goes through the same retry path with its class recorded,
 *   so the job doesn't sit in RUNNING until the reaper finds it.
 * - InterruptedException writes nothing. Only the worker itself interrupts a run
 *   (shutdown, lost lease), so it must not use up one of the job's attempts; the
 *   job stays RUNNING and the reaper recovers it when the lease runs out.
 *
 * Database errors from the writes themselves are not caught here: they are not the
 * job's fault, and the caller decides how to back off.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobStore store;
    private final HandlerRegistry handlers;
    private final RetryBackoff backoff;
    private final ObjectMapper objectMapper;

    public JobRunner(JobStore store, HandlerRegistry handlers, RetryBackoff backoff, ObjectMapper objectMapper) {
        this.store = store;
        this.handlers = handlers;
        this.backoff = backoff;
        this.objectMapper = objectMapper;
    }

    public RunOutcome run(ClaimedJob job) {
        JsonNode result;
        try {
            JobContext context = new JobContext(
                    job.jobId(), job.generation(), job.attempt(), objectMapper.readTree(job.payload()));
            result = handlers.forType(job.type()).handle(context);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Run of job {} attempt {} interrupted; leaving it to the reaper", job.jobId(), job.attempt());
            return RunOutcome.INTERRUPTED;
        } catch (JobFailedException e) {
            return fail(job, e.getMessage());
        } catch (Exception e) {
            // A handler bug: last_error gets the summary, the log gets the stack trace
            log.warn("Handler for job {} attempt {} threw unexpectedly", job.jobId(), job.attempt(), e);
            return fail(job, describe(e));
        }

        if (result == null) {
            return fail(job, "Handler returned no result");
        }
        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return fail(job, describe(e));
        }

        if (!store.complete(job, resultJson)) {
            log.warn("Job {} attempt {} finished, but this worker no longer owns it; result discarded",
                    job.jobId(), job.attempt());
            return RunOutcome.LOST;
        }
        return RunOutcome.SUCCEEDED;
    }

    private RunOutcome fail(ClaimedJob job, String error) {
        FailOutcome outcome = store.fail(job, error, backoff.delayAfter(job.attempt()));
        log.info("Job {} attempt {} failed ({}): {}", job.jobId(), job.attempt(), outcome, error);
        return switch (outcome) {
            case RETRY_SCHEDULED -> RunOutcome.RETRY_SCHEDULED;
            case DEAD -> RunOutcome.DEAD;
            case LOST -> RunOutcome.LOST;
        };
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getName() : e.getClass().getName() + ": " + e.getMessage();
    }
}
