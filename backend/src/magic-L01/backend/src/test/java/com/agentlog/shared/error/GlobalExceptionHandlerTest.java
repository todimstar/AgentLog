/*
正式目标文件：
  C:\Ep\Code\Java\AgentLog\AgentLog_start\backend\src\test\java\com\agentlog\shared\error\GlobalExceptionHandlerTest.java

这是 L01 新增测试文件。
*/

package com.agentlog.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(GlobalExceptionHandlerTest.ProblemProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc;

    GlobalExceptionHandlerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
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

/*
师傅解释：
  这个测试不是测某个业务接口。
  它造了一个最小 Controller，故意抛 ApiException。
  然后验证全局异常处理器会输出统一 ProblemDetail 形状。
*/
