ALTER TABLE jobs RENAME COLUMN state TO status;
ALTER TABLE jobs RENAME COLUMN available_at TO next_retry_at;
ALTER TABLE jobs RENAME COLUMN lease_expires_at TO lease_until;

ALTER TABLE jobs ALTER COLUMN next_retry_at DROP NOT NULL;
ALTER TABLE jobs ALTER COLUMN scheduled_at DROP NOT NULL;

ALTER TABLE jobs ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN completed_at TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN worker_id VARCHAR(64);
ALTER TABLE jobs ADD COLUMN idempotency_key VARCHAR(128);

ALTER TABLE jobs DROP CONSTRAINT jobs_state_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_status_valid
    CHECK (status IN ('SCHEDULED', 'QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD_LETTERED'));
ALTER TABLE jobs ADD CONSTRAINT jobs_scheduled_requires_time
    CHECK (status <> 'SCHEDULED' OR scheduled_at IS NOT NULL);

CREATE UNIQUE INDEX uq_jobs_idempotency_key ON jobs (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER INDEX idx_jobs_state_scheduled_at RENAME TO idx_jobs_status_scheduled_at;
ALTER INDEX idx_jobs_state_available_at RENAME TO idx_jobs_status_next_retry_at;
ALTER INDEX idx_jobs_state_lease RENAME TO idx_jobs_status_lease;

ALTER TABLE job_attempts RENAME COLUMN outcome TO status;
ALTER TABLE job_attempts ADD COLUMN duration_ms BIGINT;
UPDATE job_attempts SET status = 'RUNNING' WHERE status IS NULL;
ALTER TABLE job_attempts ALTER COLUMN status SET NOT NULL;
ALTER TABLE job_attempts DROP CONSTRAINT job_attempts_outcome_valid;
ALTER TABLE job_attempts ADD CONSTRAINT job_attempts_status_valid
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED'));

ALTER TABLE job_outbox RENAME TO outbox_messages;
ALTER TABLE outbox_messages ADD COLUMN destination VARCHAR(32) NOT NULL DEFAULT 'jobs:ready';
ALTER TABLE outbox_messages ADD COLUMN publish_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox_messages ADD COLUMN last_error TEXT;
ALTER TABLE outbox_messages ADD CONSTRAINT outbox_destination_valid
    CHECK (destination IN ('jobs:ready', 'jobs:dead-letter'));

DROP INDEX idx_job_outbox_unpublished;
CREATE INDEX idx_outbox_messages_unpublished ON outbox_messages (created_at)
    WHERE published_at IS NULL;
