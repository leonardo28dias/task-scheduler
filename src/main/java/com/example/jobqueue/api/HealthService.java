package com.example.jobqueue.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private static final String SQL_PING = "SELECT 1";

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    public HealthService(JdbcTemplate jdbcTemplate, RedisConnectionFactory redisConnectionFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    public HealthResponse getHealth() {
        Map<String, ComponentStatus> components = new LinkedHashMap<>();
        components.put("api", ComponentStatus.up());
        components.put("postgres", checkPostgres());
        components.put("redis", checkRedis());

        boolean up = components.values().stream().allMatch(component -> "UP".equals(component.status()));
        return new HealthResponse(up ? "UP" : "DOWN", Instant.now(), components);
    }

    ComponentStatus checkPostgres() {
        try {
            Integer result = jdbcTemplate.queryForObject(SQL_PING, Integer.class);
            if (Integer.valueOf(1).equals(result)) {
                return ComponentStatus.up();
            }
            return ComponentStatus.down("Unexpected response to " + SQL_PING);
        } catch (RuntimeException e) {
            return ComponentStatus.down(messageOf(e));
        }
    }

    ComponentStatus checkRedis() {
        RedisConnection connection = null;
        try {
            connection = redisConnectionFactory.getConnection();
            connection.ping();
            return ComponentStatus.up();
        } catch (RuntimeException e) {
            return ComponentStatus.down(messageOf(e));
        } finally {
            closeQuietly(connection);
        }
    }

    private String messageOf(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private void closeQuietly(RedisConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (RuntimeException ignored) {
        }
    }
}
