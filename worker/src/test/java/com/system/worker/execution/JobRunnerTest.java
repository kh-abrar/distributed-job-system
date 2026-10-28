package com.system.worker.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.common.enums.JobType;
import com.system.worker.AbstractWorkerIntegrationTest;
import com.system.worker.handler.CpuHandler;
import com.system.worker.handler.FlakyHandler;
import com.system.worker.handler.HandlerRegistry;
import com.system.worker.handler.JobContext;
import com.system.worker.handler.JobFailedException;
import com.system.worker.handler.JobHandler;
import com.system.worker.store.ClaimedJob;
import com.system.worker.store.JobStore;
import com.system.worker.store.RetryBackoff;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a handler's result or exception becomes a fenced complete or fail
 * (FR-18 to FR-21, decision 2: unexpected exceptions are failures too).
 */
class JobRunnerTest extends AbstractWorkerIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    // Fixed 10 s backoff so the retry time is predictable
    private static final RetryBackoff TEN_SECONDS =
            new RetryBackoff(Duration.ofSeconds(10), Duration.ofSeconds(10), () -> 1.0);

    @Autowired
    private JobStore store;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobRunner springRunner;

    /** A runner whose IO jobs are handled by the given test handler. */
    private JobRunner runnerWithIoHandler(JobHandler ioHandler) {
        HandlerRegistry registry = new HandlerRegistry(List.of(new CpuHandler(), new FlakyHandler(), ioHandler));
        return new JobRunner(store, registry, TEN_SECONDS, objectMapper);
    }

    private static JobHandler ioHandler(ThrowingHandle body) {
        return new JobHandler() {
            @Override
            public JobType type() {
                return JobType.IO;
            }

            @Override
            public JsonNode handle(JobContext context) throws JobFailedException, InterruptedException {
                return body.handle(context);
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingHandle {
        JsonNode handle(JobContext context) throws JobFailedException, InterruptedException;
    }

    private ClaimedJob claim(String payload) {
        insertPendingJob("IO", payload);
        return store.claim("worker-A", 1, LEASE).get(0);
    }

    private String lastError(UUID id) {
        return jdbc.queryForObject("SELECT last_error FROM jobs WHERE id = ?", String.class, id);
    }

    @Test
    void successfulRunStoresTheResult() {
        ClaimedJob job = claim("{\"n\":21}");
        JobRunner runner = runnerWithIoHandler(ioHandler(ctx ->
                objectMapper.createObjectNode().put("doubled", ctx.payload().path("n").asInt() * 2)));

        assertThat(runner.run(job)).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(statusOf(job.jobId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT result ->> 'doubled' FROM jobs WHERE id = ?",
                String.class, job.jobId())).isEqualTo("42");
    }

    @Test
    void handlerGetsTheRunIdentity() {
        ClaimedJob job = claim("{}");
        JobContext[] seen = new JobContext[1];
        JobRunner runner = runnerWithIoHandler(ioHandler(ctx -> {
            seen[0] = ctx;
            return objectMapper.createObjectNode();
        }));

        runner.run(job);

        assertThat(seen[0].jobId()).isEqualTo(job.jobId());
        assertThat(seen[0].generation()).isEqualTo(job.generation());
        assertThat(seen[0].attempt()).isEqualTo(job.attempt());
    }

    @Test
    void expectedFailureSchedulesARetryAfterTheBackoff() {
        ClaimedJob job = claim("{}");
        JobRunner runner = runnerWithIoHandler(ioHandler(ctx -> {
            throw new JobFailedException("disk full");
        }));

        assertThat(runner.run(job)).isEqualTo(RunOutcome.RETRY_SCHEDULED);
        assertThat(lastError(job.jobId())).isEqualTo("disk full");
        assertThat(jdbc.queryForObject(
                "SELECT run_at BETWEEN now() + interval '9 seconds' AND now() + interval '11 seconds' FROM jobs WHERE id = ?",
                Boolean.class, job.jobId())).isTrue();
    }

    /** Decision 2: a handler bug goes through the same retry path, with the exception class recorded. */
    @Test
    void unexpectedExceptionIsRecordedAsAFailure() {
        ClaimedJob job = claim("{}");
        JobRunner runner = runnerWithIoHandler(ioHandler(ctx -> {
            throw new IllegalStateException("kaboom");
        }));

        assertThat(runner.run(job)).isEqualTo(RunOutcome.RETRY_SCHEDULED);
        assertThat(lastError(job.jobId())).isEqualTo("java.lang.IllegalStateException: kaboom");
    }

    @Test
    void nullResultIsAFailure() {
        ClaimedJob job = claim("{}");
        JobRunner runner = runnerWithIoHandler(ioHandler(ctx -> null));

        assertThat(runner.run(job)).isEqualTo(RunOutcome.RETRY_SCHEDULED);
        assertThat(lastError(job.jobId())).contains("no result");
    }

    @Test
    void failureOnTheLastAttemptEndsDead() {
        insertPendingJob("IO", "{}", 1);
        ClaimedJob job = store.claim("worker-A", 1, LEASE).get(0);
        JobRunner runner = runnerWithIoHandler(ioHandler(ctx -> {
            throw new JobFailedException("still broken");
        }));

        assertThat(runner.run(job)).isEqualTo(RunOutcome.DEAD);
        assertThat(statusOf(job.jobId())).isEqualTo("DEAD");
    }

    /**
     * While the handler runs, its lease expires and worker B takes the job over.
     * The finished run's complete is fenced out, and B's claim is untouched.
     */
    @Test
    void runThatLostItsLeaseWritesNothing() {
        ClaimedJob job = claim("{}");
        JobRunner runner = runnerWithIoHandler(ioHandler(ctx -> {
            expireLease(ctx.jobId());
            store.reapExpired(10);
            store.claim("worker-B", 1, LEASE);
            return objectMapper.createObjectNode().put("from", "A");
        }));

        assertThat(runner.run(job)).isEqualTo(RunOutcome.LOST);
        assertThat(jdbc.queryForMap("SELECT status, locked_by, result FROM jobs WHERE id = ?", job.jobId()))
                .containsEntry("status", "RUNNING").containsEntry("locked_by", "worker-B")
                .containsEntry("result", null);
    }

    /** Interruption comes from the worker itself, so it must not use up one of the job's attempts. */
    @Test
    void interruptedRunWritesNothing() {
        ClaimedJob job = claim("{}");
        JobRunner runner = runnerWithIoHandler(ioHandler(ctx -> {
            throw new InterruptedException();
        }));

        assertThat(runner.run(job)).isEqualTo(RunOutcome.INTERRUPTED);
        assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
        assertThat(jdbc.queryForMap("SELECT status, attempts, last_error FROM jobs WHERE id = ?", job.jobId()))
                .containsEntry("status", "RUNNING").containsEntry("attempts", 1).containsEntry("last_error", null);
        assertThat(jdbc.queryForObject("SELECT outcome FROM job_attempts WHERE job_id = ?",
                String.class, job.jobId())).isNull();
    }

    /** The runner Spring builds, with the real handlers, runs a real IO job end to end. */
    @Test
    void springWiredRunnerRunsARealIoJob() {
        ClaimedJob job = claim("{\"sleepMs\":0}");

        assertThat(springRunner.run(job)).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT result ->> 'sleptMs' FROM jobs WHERE id = ?",
                String.class, job.jobId())).isEqualTo("0");
    }
}
