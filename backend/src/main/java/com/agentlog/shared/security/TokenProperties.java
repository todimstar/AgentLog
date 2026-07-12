package com.agentlog.shared.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Opaque Token 配置（绑定 application.yml 的 agentlog.token.*）。L12 起，CLI/Agent 双轨认证的地基。
 *
 * pepper：服务端密钥，参与 HMAC-SHA256(pepper, 明文token) 算摘要。只有持 pepper 的服务端能从明文
 *   算出库里存的摘要——即使脱库，攻击者没 pepper 也无法用摘要反推/伪造明文令牌（见 {@link TokenService}）。
 *   真实 pepper 走 env var（AGENTLOG_TOKEN_PEPPER）；本地放 application-local.yml，测试放 application-test.yml。
 *
 * 各 TTL：不同令牌的有效期，集中声明在此（yml 已预置），各自的课再消费——
 *   本课（L12）只用 ownerAccess/ownerRefresh + devicePairing；agentActing 属 L13，handoff/lease 属 L15。
 */
@ConfigurationProperties(prefix = "agentlog.token")
public class TokenProperties {

    /** HMAC pepper（服务端密钥）。 */
    private String pepper;

    /** OwnerAccessToken 有效期（短，默认 1h）。 */
    private Duration ownerAccessTtl = Duration.ofHours(1);

    /** OwnerRefreshToken 有效期（长，默认 30d；L13 用它换新 access）。 */
    private Duration ownerRefreshTtl = Duration.ofDays(30);

    /** AgentActingToken 有效期（L13 assume 机娘）。 */
    private Duration agentActingTtl = Duration.ofHours(1);

    /** HandoffToken 有效期（L15 ACPP 接力）。 */
    private Duration handoffTtl = Duration.ofHours(24);

    /** Attempt 租约有效期（L15 领棒）。 */
    private Duration attemptLeaseTtl = Duration.ofMinutes(15);

    /** DeviceCode 配对码有效期（默认 10min，验收项「过期处理」的时限）。 */
    private Duration devicePairingTtl = Duration.ofMinutes(10);

    public String getPepper() {
        return pepper;
    }

    public void setPepper(String pepper) {
        this.pepper = pepper;
    }

    public Duration getOwnerAccessTtl() {
        return ownerAccessTtl;
    }

    public void setOwnerAccessTtl(Duration ownerAccessTtl) {
        this.ownerAccessTtl = ownerAccessTtl;
    }

    public Duration getOwnerRefreshTtl() {
        return ownerRefreshTtl;
    }

    public void setOwnerRefreshTtl(Duration ownerRefreshTtl) {
        this.ownerRefreshTtl = ownerRefreshTtl;
    }

    public Duration getAgentActingTtl() {
        return agentActingTtl;
    }

    public void setAgentActingTtl(Duration agentActingTtl) {
        this.agentActingTtl = agentActingTtl;
    }

    public Duration getHandoffTtl() {
        return handoffTtl;
    }

    public void setHandoffTtl(Duration handoffTtl) {
        this.handoffTtl = handoffTtl;
    }

    public Duration getAttemptLeaseTtl() {
        return attemptLeaseTtl;
    }

    public void setAttemptLeaseTtl(Duration attemptLeaseTtl) {
        this.attemptLeaseTtl = attemptLeaseTtl;
    }

    public Duration getDevicePairingTtl() {
        return devicePairingTtl;
    }

    public void setDevicePairingTtl(Duration devicePairingTtl) {
        this.devicePairingTtl = devicePairingTtl;
    }
}
