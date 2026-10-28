package com.system.worker.runtime;

import com.system.worker.store.ClaimedJob;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The runs this worker currently has on its handler threads, so heartbeats know
 * which leases to renew and which thread to interrupt when one is lost.
 */
@Component
public class InFlightRuns {

    private final ConcurrentMap<UUID, Run> runs = new ConcurrentHashMap<>();

    /** Call on the handler thread, just before the handler starts. */
    Run start(ClaimedJob job) {
        Run run = new Run(job, Thread.currentThread());
        runs.put(job.jobId(), run);
        return run;
    }

    /** Call on the handler thread when the run is over, before the thread takes other work. */
    void finish(Run run) {
        run.finish();
        runs.remove(run.job().jobId(), run);
    }

    List<Run> snapshot() {
        return List.copyOf(runs.values());
    }

    public int size() {
        return runs.size();
    }

    /**
     * One run on one thread. Interrupting and finishing are synchronized, so an
     * interrupt meant for this run can never reach the thread after it has moved
     * on to another job.
     */
    static final class Run {

        private final ClaimedJob job;
        private final Thread thread;
        private boolean finished;

        private Run(ClaimedJob job, Thread thread) {
            this.job = job;
            this.thread = thread;
        }

        ClaimedJob job() {
            return job;
        }

        /** @return true if the run was still going and has been interrupted */
        synchronized boolean interruptIfRunning() {
            if (finished) {
                return false;
            }
            thread.interrupt();
            return true;
        }

        private synchronized void finish() {
            finished = true;
        }
    }
}
