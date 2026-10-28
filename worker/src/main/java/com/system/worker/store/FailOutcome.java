package com.system.worker.store;

/**
 * What a fenced failure write did.
 */
public enum FailOutcome {
    /** Back to PENDING; claimable again after the backoff (FR-21). */
    RETRY_SCHEDULED,
    /** Out of attempts; DEAD and never claimed automatically again (FR-23). */
    DEAD,
    /** The worker no longer owned this run, so nothing was written (FR-26). */
    LOST
}
