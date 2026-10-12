package com.example.jobqueue.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Test
    void returnsOkWithUpStatusWhenAllComponentsAreUp() throws Exception {
        when(healthService.getHealth()).thenReturn(healthResponse("UP", null));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.api.status").value("UP"))
                .andExpect(jsonPath("$.components.postgres.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }

    @Test
    void returnsServiceUnavailableWhenAnyComponentIsDown() throws Exception {
        when(healthService.getHealth())
                .thenReturn(healthResponse("DOWN", "connection refused"));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.postgres.status").value("DOWN"))
                .andExpect(jsonPath("$.components.postgres.message").value("connection refused"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }

    private HealthResponse healthResponse(String status, String postgresMessage) {
        ComponentStatus postgres = postgresMessage == null
                ? ComponentStatus.up()
                : ComponentStatus.down(postgresMessage);
        return new HealthResponse(status, Instant.now(), Map.of(
                "api", ComponentStatus.up(),
                "postgres", postgres,
                "redis", ComponentStatus.up()));
    }
}
