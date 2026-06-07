/*
正式目标文件：
  C:\Ep\Code\Java\AgentLog\AgentLog_start\backend\src\test\java\com\agentlog\interfaces\SystemControllerTest.java

这是 L01 新增测试文件。
*/

package com.agentlog.interfaces;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentlog.shared.security.ApiSecurityConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemController.class)
@Import({ApiSecurityConfiguration.class, SystemControllerTest.FixedClockConfiguration.class})
class SystemControllerTest {

    private final MockMvc mockMvc;

    SystemControllerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

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

/*
师傅解释：
  这个测试证明两件事：
    1. /api/v1/system/ping 可以返回 ok。
    2. Clock 可以被测试固定，所以 serverTime 是稳定的。

  这是 MVC 层测试，不启动完整服务器。
*/
