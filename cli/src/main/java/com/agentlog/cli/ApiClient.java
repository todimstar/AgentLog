package com.agentlog.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * CLI 的后端 HTTP 客户端（JDK HttpClient + Jackson）。
 *
 * 不依赖 Spring/OpenAPI 生成代码——CLI 是独立轻量程序，手写最小 HTTP 调用即可。
 * L12 只需匿名 POST JSON（配对发起 + 轮询）；带 Bearer 令牌的调用留 L13。
 */
public class ApiClient {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** POST JSON，返回 status code + 解析后的 JsonNode。 */
    public Result postJson(String path, String jsonBody) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = resp.body() == null || resp.body().isBlank()
                    ? mapper.createObjectNode() : mapper.readTree(resp.body());
            return new Result(resp.statusCode(), body);
        } catch (Exception e) {
            throw new RuntimeException("请求失败: " + path + " — " + e.getMessage(), e);
        }
    }

    /** HTTP 结果包装。 */
    public record Result(int status, JsonNode body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }
}
