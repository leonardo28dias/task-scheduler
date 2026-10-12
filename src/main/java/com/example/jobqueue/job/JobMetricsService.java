package com.example.jobqueue.job;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JobMetricsService {

    private final JdbcTemplate jdbcTemplate;

    public JobMetricsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public JobMetrics collect() {
        Map<String, Long> byStatus = jdbcTemplate.query(
                "SELECT status, count(*) AS total FROM jobs GROUP BY status",
                rs -> {
                    Map<String, Long> counts = new java.util.HashMap<>();
                    while (rs.next()) {
                        counts.put(rs.getString("status"), rs.getLong("total"));
                    }
                    return counts;
                });
        long totalAttempts = count("SELECT count(*) FROM job_attempts");
        Double averageDuration = jdbcTemplate.query(
                "SELECT avg(duration_ms) AS average FROM job_attempts WHERE status = 'SUCCEEDED'",
                rs -> rs.next() ? (Double) rs.getObject("average") : null);
        long completedLastMinute = count(
                "SELECT count(*) FROM jobs WHERE completed_at >= now() - INTERVAL '60 seconds'");

        return new JobMetrics(
                sum(byStatus),
                byStatus.getOrDefault("QUEUED", 0L),
                byStatus.getOrDefault("RUNNING", 0L),
                byStatus.getOrDefault("RETRY_WAIT", 0L),
                byStatus.getOrDefault("SUCCEEDED", 0L),
                byStatus.getOrDefault("DEAD_LETTERED", 0L),
                totalAttempts,
                averageDuration,
                completedLastMinute);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }
}
