package com.example.jobqueue.queue;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private static final RowMapper<OutboxMessage> ROW_MAPPER = (rs, rowNum) -> new OutboxMessage(
            rs.getObject("id", UUID.class),
            rs.getObject("job_id", UUID.class),
            rs.getString("destination"),
            rs.getString("payload"));

    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OutboxMessage> claimUnpublished(String claimedBy, int batchSize) {
        return jdbcTemplate.query("""
                UPDATE outbox_messages
                SET claimed_by = ?, claimed_at = now()
                WHERE id IN (
                    SELECT id FROM outbox_messages
                    WHERE published_at IS NULL
                      AND (claimed_by IS NULL OR claimed_at < now() - INTERVAL '5 minutes')
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, job_id, destination, payload
                """,
                ROW_MAPPER, claimedBy, batchSize);
    }

    public void markPublished(UUID messageId) {
        jdbcTemplate.update(
                "UPDATE outbox_messages SET published_at = now(), last_error = NULL WHERE id = ?",
                messageId);
    }

    public void recordPublishError(UUID messageId, String error) {
        jdbcTemplate.update("""
                UPDATE outbox_messages
                SET publish_attempts = publish_attempts + 1, last_error = ?, claimed_by = NULL
                WHERE id = ?
                """, error, messageId);
    }

    public record OutboxMessage(UUID id, UUID jobId, String destination, String payload) {
    }
}
