package com.system.worker.store;

import com.system.worker.AbstractWorkerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races between workers, reapers and the api, run against a real PostgreSQL.
 */
class JobStoreConcurrencyTest extends AbstractWorkerIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private JobStore store;

    /** Starts every task at the same moment and returns their results. */
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** FR-15: many workers compete, but no job is claimed twice. */
    @Test
    void competingWorkersClaimEveryJobExactlyOnce() throws Exception {
        int jobs = 50;
        for (int i = 0; i < jobs; i++) {
            insertPendingJob("IO", "{}");
        }

        List<Callable<List<UUID>>> workers = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            String workerId = "worker-" + w;
            workers.add(() -> {
                List<UUID> mine = new ArrayList<>();
                List<ClaimedJob> batch;
                while (!(batch = store.claim(workerId, 3, LEASE)).isEmpty()) {
                    batch.forEach(job -> mine.add(job.jobId()));
                }
                return mine;
            });
        }
        List<UUID> claimed = runTogether(workers).stream().flatMap(List::stream).toList();

        assertThat(claimed).hasSize(jobs).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE status = 'RUNNING' AND attempts = 1", Integer.class)).isEqualTo(jobs);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_attempts", Integer.class)).isEqualTo(jobs);
    }

    /** Several workers' reapers run at once; each expired job is recovered exactly once. */
    @Test
    void concurrentReapersRecoverEachJobOnce() throws Exception {
        int jobs = 30;
        for (int i = 0; i < jobs; i++) {
            insertPendingJob("IO", "{}");
        }
        assertThat(store.claim("worker-dead", jobs, LEASE)).hasSize(jobs);
        jdbc.update("UPDATE jobs SET lease_expires_at = now() - interval '1 second'");

        List<Callable<Integer>> reapers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            reapers.add(() -> {
                int total = 0;
                List<ReapedJob> batch;
                while (!(batch = store.reapExpired(5)).isEmpty()) {
                    total += batch.size();
                }
                return total;
            });
        }
        int reaped = runTogether(reapers).stream().mapToInt(Integer::intValue).sum();

        assertThat(reaped).isEqualTo(jobs);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job_attempts WHERE outcome = 'LEASE_EXPIRED'", Integer.class)).isEqualTo(jobs);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE status = 'PENDING'", Integer.class)).isEqualTo(jobs);
    }

    /**
     * FR-11 vs FR-15: the api's cancel and a worker's claim race for the same jobs.
     * Every job ends up either cancelled or claimed, never both and never neither.
     */
    @Test
    void cancelAndClaimNeverBothWin() throws Exception {
        int jobs = 40;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < jobs; i++) {
            ids.add(insertPendingJob("IO", "{}"));
        }
        Set<UUID> cancelled = ConcurrentHashMap.newKeySet();
        Set<UUID> claimed = ConcurrentHashMap.newKeySet();

        List<Callable<Void>> tasks = List.of(
                () -> {
                    // The api's cancel statement (api JobTransitionRepository.cancelIfPending)
                    for (UUID id : ids) {
                        if (jdbc.update("UPDATE jobs SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'", id) == 1) {
                            cancelled.add(id);
                        }
                    }
                    return null;
                },
                () -> {
                    List<ClaimedJob> batch;
                    while (!(batch = store.claim("worker-1", 1, LEASE)).isEmpty()) {
                        batch.forEach(job -> claimed.add(job.jobId()));
                    }
                    return null;
                });
        runTogether(tasks);

        Set<UUID> both = new HashSet<>(cancelled);
        both.retainAll(claimed);
        assertThat(both).isEmpty();
        assertThat(cancelled.size() + claimed.size()).isEqualTo(jobs);
        for (UUID id : cancelled) {
            assertThat(statusOf(id)).isEqualTo("CANCELLED");
        }
        for (UUID id : claimed) {
            assertThat(statusOf(id)).isEqualTo("RUNNING");
        }
    }
}
