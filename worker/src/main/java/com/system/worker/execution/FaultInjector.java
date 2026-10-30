package com.system.worker.execution;

import com.system.worker.config.WorkerProperties;
import com.system.worker.store.ClaimedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Deliberate crashes for the Phase 5 duplicate-execution experiment (E6). Off
 * unless worker.fault.crash-after-handler-probability is above 0.
 *
 * It recreates spec section 7, row 3: "worker crashes after the handler finishes
 * but before the fenced SUCCEEDED update commits". That window is a few
 * milliseconds wide, so a real crash almost never hits it; this hits it on purpose.
 * The worker then exits with Runtime.halt: no shutdown hooks, no graceful stop, as
 * close to a kill as the JVM allows. The job's lease runs out, the reaper recovers
 * it, and another worker runs it again: a duplicate run of a job whose work had
 * already finished (ADR-003).
 */
@Component
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);
    // 128 + 9, the exit status of a process killed with SIGKILL
    private static final int CRASH_EXIT_STATUS = 137;

    private final double crashAfterHandlerProbability;
    private final DoubleSupplier random;
    private final Runnable crash;

    @Autowired
    public FaultInjector(WorkerProperties properties) {
        this(properties.fault().crashAfterHandlerProbability(),
                () -> ThreadLocalRandom.current().nextDouble(),
                () -> Runtime.getRuntime().halt(CRASH_EXIT_STATUS));
        if (crashAfterHandlerProbability > 0) {
            log.warn("FAULT INJECTION ENABLED: this worker exits after {}% of successful handlers, "
                    + "before recording the result. For experiments only.", crashAfterHandlerProbability * 100);
        }
    }

    /**
     * @param random returns a value in [0, 1)
     * @param crash  what a crash does; tests replace Runtime.halt with something survivable
     */
    FaultInjector(double crashAfterHandlerProbability, DoubleSupplier random, Runnable crash) {
        this.crashAfterHandlerProbability = crashAfterHandlerProbability;
        this.random = random;
        this.crash = crash;
    }

    /** Never crashes. For code that builds a JobRunner by hand, such as tests. */
    public static FaultInjector disabled() {
        return new FaultInjector(0.0, () -> 1.0, () -> {
            throw new IllegalStateException("A disabled FaultInjector must never crash");
        });
    }

    /**
     * Call after a handler has returned its result and before the result is
     * recorded. Usually returns at once; with the configured probability, logs which
     * run it is cutting off (the experiment counts duplicates from these lines) and
     * ends the process.
     */
    void maybeCrashAfterHandler(ClaimedJob job) {
        if (crashAfterHandlerProbability > 0 && random.getAsDouble() < crashAfterHandlerProbability) {
            log.error("FAULT INJECTION: job {} generation {} attempt {} finished its handler; "
                    + "exiting before recording the result", job.jobId(), job.generation(), job.attempt());
            crash.run();
        }
    }
}
