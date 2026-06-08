package com.agentlog.shared.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentlog.shared.security.ApiSecurityConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemController.class)
@Import({ApiSecurityConfiguration.class, SystemControllerTest.FixedClockConfiguration.class})
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pingReturnsOkWithServerTime() throws Exception {
        mockMvc.perform(get("/api/v1/system/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.serverTime").value("2026-06-06T00:00:00Z"));
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        Clock systemClock() {
            return Clock.fixed(Instant.parse("2026-06-06T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
