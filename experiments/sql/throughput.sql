-- Throughput of the last run, from the database's own timestamps (one clock).
--
-- overall:      every finished job, from the first claim to the last finish
-- steady_state: the middle 80% of finishes (10th to 90th percentile), which
--               leaves out the ramp-up and the tail where workers sit idle

WITH finished AS (
    SELECT a.started_at, a.finished_at
    FROM job_attempts a
    JOIN jobs j ON j.id = a.job_id
    WHERE j.status = 'SUCCEEDED'
      AND a.outcome = 'SUCCEEDED'
), bounds AS (
    SELECT count(*)                                               AS jobs,
           min(started_at)                                        AS first_claim,
           max(finished_at)                                       AS last_finish,
           percentile_cont(0.1) WITHIN GROUP (ORDER BY extract(epoch FROM finished_at)) AS p10,
           percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM finished_at)) AS p90
    FROM finished
)
SELECT jobs                                                               AS succeeded_jobs,
       round(extract(epoch FROM last_finish - first_claim)::numeric, 3)  AS overall_seconds,
       round((jobs / nullif(extract(epoch FROM last_finish - first_claim), 0))::numeric, 1)
                                                                          AS overall_jobs_per_second,
       round(((0.8 * jobs) / nullif(p90 - p10, 0))::numeric, 1)          AS steady_state_jobs_per_second
FROM bounds;

-- Anything that didn't succeed, which would make the numbers above misleading
SELECT status, count(*) AS jobs
FROM jobs
GROUP BY status
ORDER BY status;
