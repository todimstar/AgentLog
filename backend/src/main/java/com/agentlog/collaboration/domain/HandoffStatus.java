package com.agentlog.collaboration.domain;

/**
 * 接力棒（HandoffToken）状态字典。对应 handoff_token.status 列（V012 + ck_handoff_status）。
 *
 * ★ 这五个状态回答的问题是「**还能不能有新人进来接力**」——
 *   和 TicketStatus 回答的「**已经进来的人能不能写**」是两件正交的事。
 *   这正是 ticket 与 handoff 必须分两张表的第三个理由（前两个见 V012 文件头注释）。
 *
 * ★ 状态机（Pack 10-reliability/ACPP状态机.md）：
 * <pre>
 *   AVAILABLE → CONSUMED    被成功消费（一次性，终态）
 *   AVAILABLE → FROZEN      前序失败 → 冻结，禁止新人进来（L17）
 *   FROZEN    → AVAILABLE   主人 retry 后解冻（L18）
 *   AVAILABLE → REVOKED     主动吊销（L18 terminate / L20 发布时吊销尾令牌）
 *   AVAILABLE → EXPIRED     超过 24h（L17 清理 Worker 标记）
 * </pre>
 *
 * ⚠️ 关于 EXPIRED 的一个重要实现约定：
 *   **消费路径不依赖这个状态值**。原子 UPDATE 的 WHERE 里直接写 `expires_at >= NOW(3)` 做惰性判定，
 *   所以即使清理 Worker 还没来得及把过期令牌标成 EXPIRED，消费也一定会失败。
 *   EXPIRED 只是给主人/审计看的显式标记（L17）。
 *   这样设计的好处：**正确性不依赖 Worker 是否及时**——L15 的 forbidden 明确「不写 Worker」，
 *   而本课的安全性完全成立，正是因为把时效判定放进了那条 WHERE。
 */
public enum HandoffStatus {

    /** 可被消费（签发时的初态）。 */
    AVAILABLE("AVAILABLE"),

    /** 已被消费（终态·一次性）。 */
    CONSUMED("CONSUMED"),

    /** 因前序失败被冻结，禁止新人进来（L17），主人 retry 后解冻（L18）。 */
    FROZEN("FROZEN"),

    /** 主动吊销（L18 terminate / L20 发布时吊销尾令牌）。 */
    REVOKED("REVOKED"),

    /** 超过 TTL（L17 清理 Worker 标记；消费路径靠 WHERE 惰性判定，不依赖此值）。 */
    EXPIRED("EXPIRED");

    private final String code;

    HandoffStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
