CREATE TABLE jobs
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type             VARCHAR(64)  NOT NULL,
    payload          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    state            VARCHAR(16)  NOT NULL,
    max_attempts     INTEGER      NOT NULL DEFAULT 3,
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    scheduled_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    available_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lease_expires_at TIMESTAMPTZ,
    last_error       TEXT,
    result           JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT jobs_state_valid
        CHECK (state IN ('SCHEDULED', 'QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD_LETTERED')),
    CONSTRAINT jobs_attempts_valid
        CHECK (attempt_count >= 0 AND max_attempts >= 1)
);

CREATE INDEX idx_jobs_state_scheduled_at ON jobs (state, scheduled_at);
CREATE INDEX idx_jobs_state_available_at ON jobs (state, available_at);
CREATE INDEX idx_jobs_state_lease ON jobs (state, lease_expires_at);

CREATE TABLE job_outbox
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id       UUID        NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_job_outbox_unpublished ON job_outbox (id)
    WHERE published_at IS NULL;

CREATE TABLE job_attempts
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id         UUID        NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    attempt_number INTEGER     NOT NULL,
    worker_id      VARCHAR(64),
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    outcome        VARCHAR(16),
    error_message  TEXT,
    CONSTRAINT job_attempts_unique UNIQUE (job_id, attempt_number),
    CONSTRAINT job_attempts_outcome_valid
        CHECK (outcome IS NULL OR outcome IN ('SUCCEEDED', 'FAILED', 'INTERRUPTED'))
);
