package com.example.jobqueue.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthServiceTest {

    private JdbcTemplate jdbcTemplate;
    private RedisConnectionFactory redisConnectionFactory;
    private RedisConnection redisConnection;
    private HealthService healthService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        redisConnectionFactory = mock(RedisConnectionFactory.class);
        redisConnection = mock(RedisConnection.class);
        healthService = new HealthService(jdbcTemplate, redisConnectionFactory);

        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");
    }

    @Test
    void reportsUpWhenAllDependenciesAreReachable() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        HealthResponse response = healthService.getHealth();

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.components()).containsOnlyKeys("api", "postgres", "redis");
        assertThat(response.components().get("api").status()).isEqualTo("UP");
        assertThat(response.components().get("postgres").status()).isEqualTo("UP");
        assertThat(response.components().get("redis").status()).isEqualTo("UP");
        verify(redisConnection).close();
    }

    @Test
    void reportsDownWhenPostgresIsUnreachable() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        HealthResponse response = healthService.getHealth();

        assertThat(response.status()).isEqualTo("DOWN");
        assertThat(response.components().get("postgres").status()).isEqualTo("DOWN");
        assertThat(response.components().get("postgres").message()).isEqualTo("connection refused");
        assertThat(response.components().get("redis").status()).isEqualTo("UP");
    }

    @Test
    void reportsDownWhenRedisIsUnreachable() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(redisConnectionFactory.getConnection())
                .thenThrow(new RedisConnectionFailureException("unable to connect"));

        HealthResponse response = healthService.getHealth();

        assertThat(response.status()).isEqualTo("DOWN");
        assertThat(response.components().get("postgres").status()).isEqualTo("UP");
        assertThat(response.components().get("redis").status()).isEqualTo("DOWN");
        assertThat(response.components().get("redis").message()).isEqualTo("unable to connect");
    }

    @Test
    void reportsDownWhenBothDependenciesAreUnreachable() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        when(redisConnectionFactory.getConnection())
                .thenThrow(new RedisConnectionFailureException("unable to connect"));

        HealthResponse response = healthService.getHealth();

        assertThat(response.status()).isEqualTo("DOWN");
        assertThat(response.components().get("postgres").status()).isEqualTo("DOWN");
        assertThat(response.components().get("redis").status()).isEqualTo("DOWN");
    }

    @Test
    void reportsDownWhenPostgresAnswersUnexpectedValue() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);

        HealthResponse response = healthService.getHealth();

        assertThat(response.status()).isEqualTo("DOWN");
        assertThat(response.components().get("postgres").status()).isEqualTo("DOWN");
        assertThat(response.components().get("postgres").message()).contains("Unexpected response");
    }

    @Test
    void closesRedisConnectionEvenWhenPingFails() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(redisConnection.ping()).thenThrow(new RedisConnectionFailureException("broken connection"));

        HealthResponse response = healthService.getHealth();

        assertThat(response.components().get("redis").status()).isEqualTo("DOWN");
        verify(redisConnection).close();
    }
}
