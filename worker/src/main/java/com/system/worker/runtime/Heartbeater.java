package com.system.worker.runtime;

import com.system.worker.config.WorkerProperties;
import com.system.worker.store.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Renews the lease of every run in flight (FR-17).
 *
 * A heartbeat that changes no row means this worker no longer owns the run: the
 * reaper recovered it and possibly another worker claimed it. The handler is then
 * interrupted, because anything it writes would be fenced out anyway (FR-26).
 */
@Component
public class Heartbeater {

    private static final Logger log = LoggerFactory.getLogger(Heartbeater.class);

    private final JobStore store;
    private final InFlightRuns inFlight;
    private final WorkerProperties properties;

    public Heartbeater(JobStore store, InFlightRuns inFlight, WorkerProperties properties) {
        this.store = store;
        this.inFlight = inFlight;
        this.properties = properties;
    }

    void beatAll() {
        for (InFlightRuns.Run run : inFlight.snapshot()) {
            try {
                if (!store.heartbeat(run.job(), properties.lease()) && run.interruptIfRunning()) {
                    log.warn("Lost the lease on job {} attempt {}; interrupting its run",
                            run.job().jobId(), run.job().attempt());
                }
            } catch (RuntimeException e) {
                // Keep beating the others. If the database stays unreachable the lease
                // runs out and the job is recovered: the failure model expects that.
                log.warn("Heartbeat for job {} failed: {}", run.job().jobId(), e.getMessage());
            }
        }
    }
}
