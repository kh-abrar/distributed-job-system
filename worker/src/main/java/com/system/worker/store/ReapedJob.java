package com.system.worker.store;

import com.system.common.enums.JobStatus;

import java.util.UUID;

/**
 * A job the reaper recovered from an expired lease.
 *
 * @param status PENDING if it has attempts left, otherwise DEAD
 */
public record ReapedJob(
        UUID jobId,
        String previousWorker,
        JobStatus status) {
}
