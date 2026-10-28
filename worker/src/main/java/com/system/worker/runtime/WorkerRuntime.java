package com.system.worker.runtime;

import com.system.worker.config.WorkerId;
import com.system.worker.config.WorkerProperties;
import com.system.worker.execution.JobRunner;
import com.system.worker.store.ClaimedJob;
import com.system.worker.store.JobStore;
import com.system.worker.store.RetryBackoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The running worker: a poll loop that claims jobs, a pool of handler threads,
 * and timers for heartbeats and the reaper. Started and stopped with the Spring
 * context, so a SIGTERM shuts it down gracefully.
 *
 * Claims are sized to free handler threads. Claiming more than can start at once
 * would leave the extra jobs waiting in memory while their leases run down.
 */
@Component
@ConditionalOnProperty(prefix = "worker", name = "enabled", havingValue = "true")
public class WorkerRuntime implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerRuntime.class);

    private final WorkerProperties properties;
    private final WorkerId workerId;
    private final JobStore store;
    private final JobRunner runner;
    private final InFlightRuns inFlight;
    private final Heartbeater heartbeater;
    private final Reaper reaper;
    // Open Question 1: a failed poll backs off like a failed job, exponentially with jitter
    private final RetryBackoff pollBackoff;

    private volatile boolean running;
    private Semaphore freeThreads;
    private ExecutorService handlerPool;
    private ScheduledExecutorService timers;
    private Thread poller;

    public WorkerRuntime(WorkerProperties properties, WorkerId workerId, JobStore store, JobRunner runner,
                         InFlightRuns inFlight, Heartbeater heartbeater, Reaper reaper, RetryBackoff pollBackoff) {
        this.properties = properties;
        this.workerId = workerId;
        this.store = store;
        this.runner = runner;
        this.inFlight = inFlight;
        this.heartbeater = heartbeater;
        this.reaper = reaper;
        this.pollBackoff = pollBackoff;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        freeThreads = new Semaphore(properties.threads());
        handlerPool = Executors.newFixedThreadPool(properties.threads(), named("job-runner"));
        timers = Executors.newScheduledThreadPool(2, named("worker-timer"));
        schedule(heartbeater::beatAll, properties.heartbeatInterval(), properties.heartbeatInterval());
        schedule(reaper::reapOnce, Duration.ZERO, properties.reaperInterval());
        poller = new Thread(this::pollLoop, "job-poller");
        poller.start();
        log.info("Worker {} started with {} threads", workerId, properties.threads());
    }

    /**
     * Stops claiming, gives running jobs the grace period to finish (heartbeats keep
     * their leases alive meanwhile), then interrupts the rest. An interrupted run
     * writes nothing, so its job stays RUNNING until the reaper recovers it.
     */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        poller.interrupt();
        joinQuietly(poller);

        handlerPool.shutdown();
        try {
            if (!handlerPool.awaitTermination(properties.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("{} jobs still running after the {} grace period; interrupting them",
                        inFlight.size(), properties.shutdownGrace());
                handlerPool.shutdownNow();
                handlerPool.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            handlerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Only now: heartbeats had to keep running through the grace period
        timers.shutdownNow();
        log.info("Worker {} stopped", workerId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        int failedPolls = 0;
        while (running) {
            try {
                // Wait for one free thread, then take every other free one too
                freeThreads.acquire();
                int free = 1 + freeThreads.drainPermits();
                int wanted = Math.min(free, properties.claimBatchSize());
                freeThreads.release(free - wanted);

                List<ClaimedJob> claimed;
                try {
                    claimed = store.claim(workerId.value(), wanted, properties.lease());
                    failedPolls = 0;
                } catch (RuntimeException e) {
                    freeThreads.release(wanted);
                    Duration delay = pollBackoff.delayAfter(++failedPolls);
                    log.warn("Claim failed ({} in a row), retrying in {}: {}", failedPolls, delay, e.getMessage());
                    Thread.sleep(delay.toMillis());
                    continue;
                }

                freeThreads.release(wanted - claimed.size());
                claimed.forEach(this::runOnHandlerThread);
                if (claimed.isEmpty()) {
                    Thread.sleep(properties.pollInterval().toMillis());
                }
            } catch (InterruptedException e) {
                // stop() interrupts the poller; running is already false
                return;
            }
        }
    }

    /** The claim already holds one of freeThreads' permits for this job; the run gives it back. */
    private void runOnHandlerThread(ClaimedJob job) {
        handlerPool.execute(() -> {
            InFlightRuns.Run run = inFlight.start(job);
            try {
                runner.run(job);
            } catch (RuntimeException e) {
                // A failed complete or fail write; the lease will run out and the reaper recovers the job
                log.error("Could not record the outcome of job {} attempt {}", job.jobId(), job.attempt(), e);
            } finally {
                inFlight.finish(run);
                // Drop an interrupt that arrived as the run ended, so it can't hit the next job
                Thread.interrupted();
                freeThreads.release();
            }
        });
    }

    private void schedule(Runnable task, Duration initialDelay, Duration period) {
        timers.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // An exception would silently cancel the schedule
                log.warn("Scheduled worker task failed: {}", e.getMessage());
            }
        }, initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> new Thread(task, prefix + "-" + counter.incrementAndGet());
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
