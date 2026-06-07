package com.agentlog.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // standaloneSetup 直接组装受测 Controller + 全局异常处理器，不走 Spring 容器扫描。
        // 这正好隔离“ApiException 是否被翻译成统一 ProblemDetail”这一件事，快且确定。
        mockMvc = MockMvcBuilders.standaloneSetup(new ProblemProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void apiExceptionReturnsProblemDetailShape() throws Exception {
        mockMvc.perform(get("/problem-test").header("X-Request-Id", "trace-l01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://agentlog.local/problems/L01_TEST"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("L01 problem detail probe"))
                .andExpect(jsonPath("$.code").value("L01_TEST"))
                .andExpect(jsonPath("$.traceId").value("trace-l01"))
                .andExpect(jsonPath("$.recoverable").value(false));
    }

    @RestController
    static class ProblemProbeController {

        @GetMapping("/problem-test")
        void problem() {
            throw new ApiException(HttpStatus.BAD_REQUEST, "L01_TEST", "L01 problem detail probe");
        }
    }
}
