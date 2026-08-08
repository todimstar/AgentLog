package com.agentlog.collaboration.domain;

/**
 * 贡献尝试状态字典。对应 contribution_attempt.status 列（V013 + ck_attempt_status）。
 *
 * ★ Attempt 是什么：一个席位上的<b>一次写作过程</b>。
 *   票（{@link TicketStatus}）= 队列里的位置，长寿，活到文章发布之后；
 *   Attempt = 这次坐下来写的过程，短命，一次尝试的起止。
 *   一张票可能被写好几次（超时后主人 retry，L18）——票不变、attempt 重开，故 1:N 分表。
 *
 * ★ 租约（Lease）内嵌在本表里，不单开一张表：一次尝试有且仅有一个租约，
 *   租约失效即这次尝试失败——恒 1:1 且同生共死。
 *   （对比 L15 的 ticket ↔ handoff：基数会破 1:1、状态机正交，故必须分表。
 *    合与分的判据：① 基数会不会破 1:1 ② 生命周期是否同生共死，两个都满足才合表。）
 *
 * ★ 状态机（Pack 10-reliability/ACPP状态机.md）：
 * <pre>
 *   ACTIVE → SUCCEEDED        submit 成功（L16）
 *   ACTIVE → FAILED_TIMEOUT   租约超时未提交（L17 Worker 标记）
 *   ACTIVE → FAILED_CLIENT    机娘自己报告失败（L18）
 *   ACTIVE → REVOKED          主人终止协作（L18）
 * </pre>
 */
public enum AttemptStatus {

    /**
     * ⚠️ 本课<b>无产生路径</b>（DRIFT D-16）。
     *
     * 状态机文档写着 {@code READY -> ACTIVE}，但 TX-04 第 4-5 步是「创建 attempt 时就写入租约」——
     * 创建即 ACTIVE，没有任何代码能产生 READY。这是蓝图内部的一处自相矛盾，已诚实登记。
     * 保留它（含 CHECK 值集）是为 L18 retry 预留：届时可能出现「已排定但尚未领租约」的中间态。
     */
    READY("READY"),

    /** 租约在手，正在写。L16 claim lease 的初态，也是本课唯一会被创建出来的状态。 */
    ACTIVE("ACTIVE"),

    /** submit 成功（L16）。 */
    SUCCEEDED("SUCCEEDED"),

    /** 租约超时未提交（L17 Worker 标记）。★ 注意：状态不准 ≠ 行为不对——
     *  超时的租约在被标记成本状态之前就已经提交不进来了（惰性判定写在 submit 的 WHERE 里）。 */
    FAILED_TIMEOUT("FAILED_TIMEOUT"),

    /** 机娘主动报告本次尝试失败（L18）。 */
    FAILED_CLIENT("FAILED_CLIENT"),

    /** 主人终止协作导致作废（L18）。 */
    REVOKED("REVOKED");

    private final String code;

    AttemptStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
