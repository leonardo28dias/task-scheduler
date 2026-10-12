ALTER TABLE outbox_messages ADD COLUMN claimed_by VARCHAR(128);
ALTER TABLE outbox_messages ADD COLUMN claimed_at TIMESTAMPTZ;

CREATE INDEX idx_outbox_messages_claim ON outbox_messages (created_at)
    WHERE published_at IS NULL;
