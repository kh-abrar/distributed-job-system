-- V3: one history row per run.
--
-- ADR-005: (job_id, generation, attempt) identifies a run across manual requeues.
-- A claim writes exactly one job_attempts row for its run, and complete, fail and
-- the reaper update that row. The constraint makes a duplicate claim row, or a
-- second requeue row in the same generation, impossible rather than merely unlikely.
--
-- Requeue rows use attempt 0 and worker runs start at attempt 1, so the two never
-- collide within a generation.
--
-- The unique index also serves lookups of a run's row by those three columns,
-- which makes idx_job_attempts_job_id (V1) redundant: job_id is its leading column.

ALTER TABLE job_attempts
    ADD CONSTRAINT job_attempts_unique_run UNIQUE (job_id, generation, attempt);

DROP INDEX idx_job_attempts_job_id;
