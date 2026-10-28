package com.system.worker.handler;

/**
 * An expected job failure (FR-20), such as a FLAKY job failing on purpose or a
 * payload parameter out of range. The message becomes the job's last_error.
 */
public class JobFailedException extends Exception {

    public JobFailedException(String message) {
        super(message);
    }
}
