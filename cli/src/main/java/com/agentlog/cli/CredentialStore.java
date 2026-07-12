package com.agentlog.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

/**
 * CLI 本地凭据存储 —— credentials.json（对齐 Pack 07-cli/schemas/credentials.schema.json）。
 *
 * 只存敏感数据：owner access/refresh token + access 过期时间。
 *   ⚠ 是【明文存】不是加密——V0 靠「文件权限 rw------- + 绝不打印」两道**非加密**的墙保护；
 *   真正的静态加密（接系统 Keychain）是后续/生产的事（credential-policy V0）。
 * 安全（credential-policy「不打印秘密、日志过滤」）：token 只写文件、绝不打印到控制台/日志。
 *   写文件时尽力收紧权限为仅本人可读（POSIX rw-------）；Windows 不支持 POSIX 会被优雅跳过
 *   （学习项目从简，生产应改用 ACL 收紧，见 credential-policy V0）。
 *
 * L12 只存 owner 令牌；L13 扩展 refresh 轮换 + acting token。
 */
public class CredentialStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public CredentialStore() {
        this.file = CliPaths.credentialsFile();
    }

    /** 保存 owner access/refresh token（明文存本地文件，不打印）。 */
    public void saveTokens(String accessToken, String refreshToken, String accessExpiresAt) {
        try {
            ObjectNode root = load();
            root.put("ownerAccessToken", accessToken);
            root.put("ownerRefreshToken", refreshToken);
            root.put("accessExpiresAt", accessExpiresAt);
            save(root);
        } catch (Exception e) {
            throw new IllegalStateException("保存 token 失败: " + e.getMessage(), e);
        }
    }

    public String getAccessToken() {
        return readString("ownerAccessToken");
    }

    public String getAccessExpiresAt() {
        return readString("accessExpiresAt");
    }

    /** access token 是否仍在有效期内（本地判断，不查服务端；无凭据/无过期时间视为无效）。 */
    public boolean isAccessValid(Instant now) {
        String exp = getAccessExpiresAt();
        if (getAccessToken() == null || exp == null) {
            return false;
        }
        try {
            return now.isBefore(Instant.parse(exp));
        } catch (Exception e) {
            return false;
        }
    }

    private String readString(String key) {
        try {
            ObjectNode root = load();
            return root.hasNonNull(key) ? root.get(key).asText() : null;
        } catch (Exception e) {
            return null;
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
        tightenPermissions();
    }

    /** 尽力把 credentials.json 权限收紧为仅本人可读写；不支持 POSIX 的平台（Windows）优雅跳过。 */
    private void tightenPermissions() {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Windows 等不支持 POSIX 权限：学习项目从简；生产应改用 ACL 收紧。
        }
    }
}
