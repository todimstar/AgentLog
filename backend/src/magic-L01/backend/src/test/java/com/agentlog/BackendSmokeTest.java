/*
正式目标文件：
  C:\Ep\Code\Java\AgentLog\AgentLog_start\backend\src\test\java\com\agentlog\BackendSmokeTest.java

这是 L01 新增测试文件。
*/

package com.agentlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agentlog.token.pepper=test-pepper",
                "spring.flyway.enabled=false",
                "management.health.db.enabled=false"
        })
class BackendSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void actuatorHealthIsUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void systemPingIsPublicAndOk() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/system/ping", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "ok");
        assertThat(response.getBody()).containsKey("serverTime");
    }
}

/*
师傅解释：
  这是 smoke test，真的启动 Spring Boot 随机端口。
  它证明：
    /actuator/health 可以匿名访问并返回 UP。
    /api/v1/system/ping 可以匿名访问并返回 ok。

  这里在测试属性里关闭 Flyway 和 DB health，是为了让 L01 只验证后端生命体征。
  数据库生命体征留给 L03。
*/
