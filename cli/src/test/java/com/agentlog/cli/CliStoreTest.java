package com.agentlog.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLI 本地存储单测：installationCode 稳定性、config 存取、token roundtrip、过期判断、不泄漏 token。
 * 用 @TempDir 覆盖 user.home 隔离，绝不碰真实 ~/.agentlog。
 */
class CliStoreTest {

    @TempDir
    Path tempHome;

    private String originalUserHome;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void installationCodeIsStableAcrossCalls() {
        CliConfig config = new CliConfig();
        String first = config.getOrCreateInstallationCode();
        String second = config.getOrCreateInstallationCode();
        // 首次生成后持久化，二次调用（甚至新实例）读回同一个。
        assertThat(first).isNotBlank().startsWith("cli-");
        assertThat(second).isEqualTo(first);
        assertThat(new CliConfig().getOrCreateInstallationCode()).isEqualTo(first);
    }

    @Test
    void serverBaseUrlDefaultsThenRemembers() {
        CliConfig config = new CliConfig();
        assertThat(config.serverBaseUrl()).isEqualTo(CliConfig.DEFAULT_SERVER);
        config.setServerBaseUrl("http://example.com:9000");
        assertThat(new CliConfig().serverBaseUrl()).isEqualTo("http://example.com:9000");
    }

    @Test
    void tokensRoundTripAndValidityByExpiry() {
        CredentialStore store = new CredentialStore();
        assertThat(store.getAccessToken()).isNull();
        assertThat(store.isAccessValid(Instant.now())).isFalse();

        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        store.saveTokens("owner_at_secret", "owner_rt_secret", future.toString());

        assertThat(store.getAccessToken()).isEqualTo("owner_at_secret");
        assertThat(store.getAccessExpiresAt()).isEqualTo(future.toString());
        assertThat(store.isAccessValid(Instant.now())).isTrue();
        // 过期后判为无效。
        assertThat(store.isAccessValid(future.plus(1, ChronoUnit.MINUTES))).isFalse();
    }

    @Test
    void configAndCredentialsAreSeparateFiles() throws Exception {
        new CliConfig().getOrCreateInstallationCode();
        new CredentialStore().saveTokens("a", "b", Instant.now().toString());
        Path dir = tempHome.resolve(".agentlog");
        assertThat(Files.exists(dir.resolve("config.json"))).isTrue();
        assertThat(Files.exists(dir.resolve("credentials.json"))).isTrue();
        // 非密配置里不该混入 token。
        assertThat(Files.readString(dir.resolve("config.json"))).doesNotContain("ownerAccessToken");
    }
}
