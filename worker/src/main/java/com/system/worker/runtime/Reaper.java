package com.system.worker.runtime;

import com.system.worker.config.WorkerProperties;
import com.system.worker.store.JobStore;
import com.system.worker.store.ReapedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Recovers jobs whose lease ran out (FR-24, FR-25, G3). Every worker runs one;
 * JobStore.reapExpired uses SKIP LOCKED, so concurrent reapers never recover the
 * same job twice.
 */
@Component
public class Reaper {

    private static final Logger log = LoggerFactory.getLogger(Reaper.class);

    private final JobStore store;
    private final WorkerProperties properties;

    public Reaper(JobStore store, WorkerProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /** Reaps in batches until a batch comes back short, so a backlog clears in one pass. */
    void reapOnce() {
        List<ReapedJob> batch;
        do {
            batch = store.reapExpired(properties.reaperBatchSize());
            for (ReapedJob job : batch) {
                log.info("Recovered job {} from expired lease of {}; now {}",
                        job.jobId(), job.previousWorker(), job.status());
            }
        } while (batch.size() == properties.reaperBatchSize());
    }
}
