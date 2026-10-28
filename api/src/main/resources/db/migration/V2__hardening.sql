-- V2: hardening constraints for the jobs and job_attempts tables.
--
-- jobs.generation
--   FR-27 / ADR-004: a manual requeue resets attempts to 0, so (job_id, attempt)
--   repeats across requeues. generation counts manual requeues, which makes
--   (job_id, generation, attempt) unique for the whole life of a job. Handlers
--   use it to detect repeated runs (spec 1.3), and fencing can include it so a
--   worker from an earlier generation can't match a later attempt (G5).
--
-- CHECK (attempts >= 0)
--   attempts starts at 0 and only goes up at claim time (spec 1.3). A negative
--   value means a bug in the claim or requeue SQL.
--
-- CHECK (max_attempts BETWEEN 1 AND 10)
--   FR-2 / spec 8: the allowed client range. The API validates this too, but the
--   database enforces it for every writer.
--
-- CHECK (attempts <= max_attempts)
--   G6 / FR-22: automatic retries never take a job past its max attempts. A
--   claim that would push attempts above max_attempts fails instead of running.
--
-- CHECK (status = 'RUNNING' implies locked_by and lease_expires_at are set)
--   FR-16 / G3: every RUNNING job has an owner and a lease expiry. A RUNNING row
--   without a lease could never be found by the reaper and would be stuck forever.
--
-- idx_jobs_reapable
--   FR-24 / G3: the reaper polls for RUNNING jobs whose lease has expired every
--   reaper interval (5 s). The partial index keeps that query to the small set
--   of RUNNING rows.
--
-- job_attempts.generation
--   Records which generation each attempt row belongs to, so history keeps the
--   generations of a requeued job apart (ADR-004).
--
-- job_attempts.outcome adds 'MANUAL_REQUEUE'
--   FR-27: the requeue is written as its own flagged history entry, separate
--   from automatic retries.

ALTER TABLE jobs
    ADD COLUMN generation INT NOT NULL DEFAULT 0;

ALTER TABLE jobs
    ADD CONSTRAINT jobs_attempts_non_negative
        CHECK (attempts >= 0),
    ADD CONSTRAINT jobs_max_attempts_range
        CHECK (max_attempts BETWEEN 1 AND 10),
    ADD CONSTRAINT jobs_attempts_within_max
        CHECK (attempts <= max_attempts),
    ADD CONSTRAINT jobs_running_has_lease
        CHECK (status <> 'RUNNING'
               OR (locked_by IS NOT NULL AND lease_expires_at IS NOT NULL));

CREATE INDEX idx_jobs_reapable
    ON jobs (lease_expires_at)
    WHERE status = 'RUNNING';

ALTER TABLE job_attempts
    ADD COLUMN generation INT NOT NULL DEFAULT 0;

-- V1 declared this CHECK inline, so PostgreSQL named it job_attempts_outcome_check.
-- No IF EXISTS: if the name is different, the migration should fail rather than
-- leave the old constraint in place.
ALTER TABLE job_attempts
    DROP CONSTRAINT job_attempts_outcome_check;

ALTER TABLE job_attempts
    ADD CONSTRAINT job_attempts_outcome_check
        CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'LEASE_EXPIRED', 'MANUAL_REQUEUE'));
