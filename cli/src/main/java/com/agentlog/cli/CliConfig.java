package com.agentlog.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * CLI 非密配置 —— config.json（对齐 Pack 07-cli/schemas/config.schema.json）。
 *
 * 存两样非敏感信息：
 *   serverBaseUrl   —— 后端地址（首次可由 --server 写入，缺省 http://localhost:8080）。
 *   installationCode —— 本机设备标识（一个 CLI 装机一个，首次随机生成并持久化）。
 *
 * 与 {@link CredentialStore}（存 token 的密文件）分开：非密配置和密凭据职责分离，
 * 便于对 credentials.json 单独收紧文件权限，也便于人工查看/编辑非密配置。
 */
public class CliConfig {

    public static final String DEFAULT_SERVER = "http://localhost:8080";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public CliConfig() {
        this.file = CliPaths.configFile();
    }

    /** 读取 serverBaseUrl；未设则返回默认。 */
    public String serverBaseUrl() {
        try {
            ObjectNode root = load();
            return root.hasNonNull("serverBaseUrl") ? root.get("serverBaseUrl").asText() : DEFAULT_SERVER;
        } catch (Exception e) {
            return DEFAULT_SERVER;
        }
    }

    /** 记住本次 --server（写入 config.json，下次免传）。 */
    public void setServerBaseUrl(String url) {
        try {
            ObjectNode root = load();
            root.put("serverBaseUrl", url);
            save(root);
        } catch (Exception e) {
            throw new IllegalStateException("写入 serverBaseUrl 失败: " + e.getMessage(), e);
        }
    }

    /** 生成或读取本机 installationCode（一台设备一个，首次随机生成并持久化）。 */
    public String getOrCreateInstallationCode() {
        try {
            ObjectNode root = load();
            if (root.hasNonNull("installationCode")) {
                return root.get("installationCode").asText();
            }
            String code = "cli-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            root.put("installationCode", code);
            save(root);
            return code;
        } catch (Exception e) {
            throw new IllegalStateException("读取/生成 installationCode 失败: " + e.getMessage(), e);
        }
    }

    private ObjectNode load() throws Exception {
        if (Files.exists(file)) {
            return (ObjectNode) mapper.readTree(Files.readString(file));
        }
        return mapper.createObjectNode();
    }

    private void save(ObjectNode root) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }
}
