package com.agentlog.collaboration.domain;

/**
 * 贡献席位（票）状态字典。对应 contribution_ticket.status 列（V012 + ck_ticket_status）。
 *
 * ★ 一张票 = 队列里的一个位置，不是"写作许可"。
 *   顺序由票决定（predecessor_ticket_id 组成的因果链），能不能落笔由 Lease 决定（L16）。
 *   这个区分是 APPROVAL_RECORD 里那条「**后序可提前排队，不可提前写**」的直接体现——
 *   第二个 AI 可以马上入队拿到票（WAITING_PREDECESSOR），但必须等前序 DONE 才能领租约。
 *
 * ★ 状态机（Pack 10-reliability/ACPP状态机.md）：
 * <pre>
 *   [*] → CREATED
 *   CREATED → READY_TO_WRITE           无未完成前序（L15 首棒即此态）
 *   CREATED → WAITING_PREDECESSOR      有前序（L15 第二棒即此态）
 *   WAITING_PREDECESSOR → READY_TO_WRITE        前序 DONE（L16）
 *   WAITING_PREDECESSOR → BLOCKED_BY_PREDECESSOR 前序失败（L17）
 *   BLOCKED_BY_PREDECESSOR → READY_TO_WRITE      前序恢复（L18 retry）
 *   READY_TO_WRITE → LEASED            领到租约（L16）
 *   LEASED → DONE                      submit 成功（L16）
 *   LEASED → FAILED_TIMEOUT            租约超时（L17 Worker）
 *   FAILED_TIMEOUT → READY_TO_WRITE    主人 retry（L18）
 * </pre>
 * CREATED 是瞬时态：L15 建票时立刻判定有无前序，直接落 READY_TO_WRITE 或 WAITING_PREDECESSOR，
 * 不会有票停在 CREATED 上。保留它是为了忠实于状态机文档（且将来若引入"预建票"会用到）。
 */
public enum TicketStatus {

    /** 刚建、尚未判定前序的瞬时态（L15 实际不落库）。 */
    CREATED("CREATED"),

    /** 无未完成前序，可以领租约。L15 的首棒。 */
    READY_TO_WRITE("READY_TO_WRITE"),

    /** 有前序未完成，先排队。L15 的第二棒——「可提前排队，不可提前写」。 */
    WAITING_PREDECESSOR("WAITING_PREDECESSOR"),

    /** 前序失败 → 阻塞（L17）。 */
    BLOCKED_BY_PREDECESSOR("BLOCKED_BY_PREDECESSOR"),

    /** 已领租约，正在写（L16）。 */
    LEASED("LEASED"),

    /** 已 submit（L16）。 */
    DONE("DONE"),

    /** 租约超时未提交（L17 Worker 标记）。 */
    FAILED_TIMEOUT("FAILED_TIMEOUT");

    private final String code;

    TicketStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
