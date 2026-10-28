package com.system.worker.store;

import com.system.common.enums.JobType;

import java.util.UUID;

/**
 * A job this worker has claimed, plus everything its fenced writes must match:
 * the worker id, and the generation and attempt captured at claim time (ADR-005).
 *
 * @param payload the job's payload as JSON text
 */
public record ClaimedJob(
        UUID jobId,
        String workerId,
        int generation,
        int attempt,
        JobType type,
        String payload) {
}
