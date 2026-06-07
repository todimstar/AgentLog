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
                "management.health.db.enabled=false",
                // L01 只验证后端生命体征，没有数据库。Modulith 的 JDBC 事件发布会在启动期
                // 连库取元数据，本机无 MySQL 会导致容器起不来，所以这一课先排除它，留到 L03/L17。
                "spring.autoconfigure.exclude=org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"
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
