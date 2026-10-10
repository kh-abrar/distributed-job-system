CREATE TABLE jobs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key   TEXT UNIQUE,
    type              TEXT NOT NULL CHECK (type IN ('CPU', 'IO', 'FLAKY')),
    payload           JSONB NOT NULL,
    status            TEXT NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','DEAD','CANCELLED')),
    attempts          INT NOT NULL DEFAULT 0,
    max_attempts      INT NOT NULL DEFAULT 5,
    run_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_by         TEXT,
    lease_expires_at  TIMESTAMPTZ,
    result            JSONB,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_claimable
    ON jobs (run_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_jobs_status_id
    ON jobs (status, id);

CREATE TABLE job_attempts (
    id             BIGSERIAL PRIMARY KEY,
    job_id         UUID NOT NULL REFERENCES jobs(id),
    attempt        INT NOT NULL,
    worker_id      TEXT,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    outcome        TEXT CHECK (outcome IN ('SUCCEEDED','FAILED','LEASE_EXPIRED')),
    error          TEXT,
    manual_requeue BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_job_attempts_job_id
    ON job_attempts (job_id);


CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_jobs_updated_at
BEFORE UPDATE ON jobs
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();