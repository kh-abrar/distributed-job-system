package com.system.worker.execution;

/**
 * How one run of a claimed job ended.
 */
public enum RunOutcome {
    /** The handler succeeded and the fenced complete was written. */
    SUCCEEDED,
    /** The handler failed; the job is PENDING again after the backoff. */
    RETRY_SCHEDULED,
    /** The handler failed on the last attempt; the job is DEAD. */
    DEAD,
    /** This worker lost the run before it could write; nothing was written. */
    LOST,
    /** The worker interrupted the run; nothing was written and the reaper will recover the job. */
    INTERRUPTED
}
