package com.agentlog.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * auth 命令组：login（设备配对）+ status（登录状态）。
 *
 * 输出规则（cli-spec）：stdout 出机器可读 JSON；stderr 出人类诊断；**绝不打印 token**。
 *
 * login 走完整 OAuth 设备授权流：
 *   1) POST /cli/device-pairings 拿 deviceCode + userCode + verificationUri
 *   2) stderr 提示主人去浏览器打开 verificationUri、输入 userCode 批准
 *   3) 按 pollInterval 轮询 /cli/device-pairings/token，直到 APPROVED / EXPIRED / 超时
 *   4) APPROVED → 把 token 存 credentials.json（不打印），stdout 出成功 JSON
 */
@Command(name = "auth", description = "认证相关命令",
        subcommands = {AuthCommand.Login.class, AuthCommand.Status.class})
public class AuthCommand implements Runnable {

    @Override
    public void run() {
        System.err.println("用法: agentlog auth [login|status]");
    }

    @Command(name = "login", description = "设备配对登录（OAuth 设备授权流）")
    static class Login implements Callable<Integer> {

        @Option(names = "--server", description = "后端地址（首次会记住，缺省 " + CliConfig.DEFAULT_SERVER + "）")
        String server;

        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public Integer call() throws Exception {
            CliConfig config = new CliConfig();
            if (server != null && !server.isBlank()) {
                config.setServerBaseUrl(server);
            }
            String baseUrl = config.serverBaseUrl();
            String installationCode = config.getOrCreateInstallationCode();
            ApiClient api = new ApiClient(baseUrl);

            // 1) 发起配对。
            ObjectNode createBody = mapper.createObjectNode()
                    .put("installationCode", installationCode)
                    .put("deviceName", System.getProperty("os.name") + "-cli");
            ApiClient.Result pair = api.postJson("/api/v1/cli/device-pairings", createBody.toString());
            if (!pair.ok()) {
                System.err.println("✗ 配对发起失败: HTTP " + pair.status());
                return 1;
            }
            String deviceCode = pair.body().get("deviceCode").asText();
            String userCode = pair.body().get("userCode").asText();
            String verifyUri = pair.body().get("verificationUri").asText();
            int pollInterval = pair.body().path("pollIntervalSeconds").asInt(3);
            int expiresIn = pair.body().path("expiresInSeconds").asInt(600);

            // 2) 提示主人去浏览器批准（人类诊断走 stderr）。
            System.err.println();
            System.err.println("  请在浏览器打开：" + verifyUri);
            System.err.println("  输入配对码：    " + userCode);
            System.err.println("  （" + (expiresIn / 60) + " 分钟内有效，等待批准…）");
            System.err.println();

            // 3) 轮询换 token。
            long deadline = System.nanoTime() + expiresIn * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                Thread.sleep(pollInterval * 1000L);
                ApiClient.Result poll = api.postJson("/api/v1/cli/device-pairings/token",
                        mapper.createObjectNode().put("deviceCode", deviceCode).toString());
                if (!poll.ok()) {
                    System.err.println("✗ 轮询失败: HTTP " + poll.status());
                    return 1;
                }
                String status = poll.body().path("status").asText("");
                if ("APPROVED".equals(status)) {
                    JsonNode b = poll.body();
                    // 4) 存 token（不打印！）。
                    new CredentialStore().saveTokens(
                            b.get("ownerAccessToken").asText(),
                            b.get("ownerRefreshToken").asText(),
                            b.path("accessExpiresAt").asText(null));
                    System.err.println("✓ 配对成功，已登录。凭据已安全保存到本地。");
                    printJson(mapper.createObjectNode()
                            .put("status", "ok")
                            .put("installationCode", installationCode)
                            .put("accessExpiresAt", b.path("accessExpiresAt").asText(null)));
                    return 0;
                }
                if ("EXPIRED".equals(status)) {
                    System.err.println("✗ 配对码已过期，请重新运行 auth login。");
                    printJson(mapper.createObjectNode().put("status", "expired"));
                    return 1;
                }
                // PENDING：继续轮询。
                System.err.print(".");
                System.err.flush();
            }
            System.err.println("\n✗ 等待超时。");
            printJson(mapper.createObjectNode().put("status", "timeout"));
            return 1;
        }

        private void printJson(ObjectNode node) {
            System.out.println(node.toString());
        }
    }

    @Command(name = "status", description = "查看本机登录状态")
    static class Status implements Callable<Integer> {

        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public Integer call() {
            CredentialStore store = new CredentialStore();
            String token = store.getAccessToken();
            ObjectNode out = mapper.createObjectNode();
            if (token == null) {
                System.err.println("未登录。运行 agentlog auth login 进行配对。");
                out.put("status", "logged_out");
            } else {
                boolean valid = store.isAccessValid(Instant.now());
                String exp = store.getAccessExpiresAt();
                // 不打印 token 本身，只报状态 + 过期时间。
                System.err.println(valid
                        ? "已登录。Access Token 有效期至：" + exp
                        : "Access Token 已过期（需 auth refresh，L13）。有效期至：" + exp);
                out.put("status", valid ? "logged_in" : "access_expired").put("accessExpiresAt", exp);
            }
            System.out.println(out.toString());
            return 0;
        }
    }
}
