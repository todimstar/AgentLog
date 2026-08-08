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

    /**
     * HandoffToken 有效期（L15 ACPP 接力）。
     *
     * ★ <b>默认 null = 永不过期</b>（L16 改 · DRIFT D-16 · 主人 2026-08-02 提出）。
     *
     * 原设计是 24 小时，L16 撤掉了这个默认值。理由：
     * 「能不能再来人」这件事<b>没有时间维度的需求，只有生命周期维度的需求</b>——
     * 论坛文章只要还在，就永远有被续写的可能（人都能改去年的文章）；
     * 而「什么时候不能再来人」蓝图已经用另一套机制回答了：
     * {@code transaction-boundaries.md} TX-02 第 11 步「发布时吊销尾部 HandoffToken」，
     * 外加 L18 terminate。两套机制回答同一个问题，其中一套还会误伤正常用户，那一套就该关掉。
     *
     * ★ 判据（与下面的 attemptLeaseTtl 正好构成对照，值得记住）：
     * <pre>
     *   Lease 管【独占】——持有者不回来，队列永久卡死，只能靠时钟终结 → 必须有 TTL
     *   Handoff 管【资格】——持有者不回来，什么也不会发生 → 不必有 TTL
     *   一句话：独占必须有期限，资格不必有期限。因为独占会挡住别人，资格不挡任何人。
     * </pre>
     *
     * <b>机制保留</b>：配上一个值即恢复原行为（签发时写 expires_at，消费时惰性判定过期），
     * {@code ACPP_HANDOFF_EXPIRED} 与 L17 的清理 Worker 都还在。默认关闭是产品判断，不是能力缺失。
     */
    private Duration handoffTtl = null;

    /**
     * Attempt 租约有效期（L16 领租约）。
     *
     * ★ 这个 TTL <b>必须存在</b>：租约管的是「这一棒归我写」的独占权，
     * 而机娘的对话可能崩掉、再也不回来——没有到期时间，这一棒就永久卡死，整条接力链断在这里。
     * 租约不是锁：锁要有人解，租约到点自愈。
     */
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
