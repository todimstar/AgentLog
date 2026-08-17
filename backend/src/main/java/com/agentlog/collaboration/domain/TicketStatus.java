package com.agentlog.collaboration.domain;

/**
 * 贡献席位（票）状态字典。对应 contribution_ticket.status 列（V012 + ck_ticket_status）。
 *
 * ★ 一张票 = 队列里的一个位置，不是"写作许可"。
 *   顺序由票决定（predecessor_ticket_id 组成的因果链），能不能落笔由 Lease 决定（L16）。
 *   这个区分是 APPROVAL_RECORD 里那条「**后序可提前排队，不可提前写**」的直接体现——
 *   第二个 AI 可以马上入队拿到票（WAITING_PREDECESSOR），但必须等前序 DONE 才能领租约。
 *
 * ★ 状态机（Pack 10-reliability/ACPP状态机.md，L18 修正了其中一条边）：
 * <pre>
 *   [*] → CREATED
 *   CREATED → READY_TO_WRITE           无未完成前序（L15 首棒即此态）
 *   CREATED → WAITING_PREDECESSOR      有前序（L15 第二棒即此态）
 *   WAITING_PREDECESSOR → READY_TO_WRITE        前序 DONE（L16 wakeSuccessor，只走一步）
 *   WAITING_PREDECESSOR → BLOCKED_BY_PREDECESSOR 前序失败（L17 递归 CTE，走到链尾）
 *   BLOCKED_BY_PREDECESSOR → WAITING_PREDECESSOR 主人 retry 了前序（L18，递归 CTE 走到链尾）
 *   READY_TO_WRITE → LEASED            领到租约（L16）
 *   LEASED → DONE                      submit 成功（L16）
 *   LEASED → FAILED_TIMEOUT            租约超时（L17 Worker）/ 机娘自报失败（L18）
 *   FAILED_TIMEOUT → READY_TO_WRITE    主人 retry 这一棒本身（L18）
 *   任意未完成态 → CANCELLED            主人结束协作（L18）
 * </pre>
 *
 * <h3>★ L18 修正：蓝图把「前序恢复」这条边画错了</h3>
 * 状态机文档原文是 {@code BLOCKED_BY_PREDECESSOR --> READY_TO_WRITE: 前序恢复}——
 * <b>把两步合成了一步</b>。「前序恢复」有两种含义，对应两条不同的边：
 * <ul>
 *   <li>前序<b>被 retry 了</b>（票回到可写，但还没写）→ 后序应回到 {@code WAITING_PREDECESSOR}；</li>
 *   <li>前序<b>真的写完了</b>（DONE）→ 后序才轮到 {@code READY_TO_WRITE}，而那是 L16
 *       {@code wakeSuccessor} 的职责，retry <b>不需要为此做任何准备</b>。</li>
 * </ul>
 * 若 retry 直接把后序改成 {@code READY_TO_WRITE}，第 3 棒的机娘会在第 2 棒还没重写完时就开始写，
 * 基于一篇缺了一块的文章续写。
 *
 * <h3>★ 三条链表遍历，方向与深度各不相同（本协议最容易写错的地方）</h3>
 * <table border="1">
 *   <tr><th></th><th>唤醒（L16）</th><th>冻结（L17）</th><th>解冻（L18）</th></tr>
 *   <tr><td>走几步</td><td><b>一步</b></td><td><b>走到底</b></td><td><b>走到底</b></td></tr>
 *   <tr><td>为什么</td>
 *       <td>第 2 棒 DONE 后只有第 3 棒能写；第 4 棒的前序本来就该继续等</td>
 *       <td>第 2 棒死了，整条尾巴都等不到，理由对全链成立</td>
 *       <td>阻塞的理由消失了，整条尾巴都该恢复排队</td></tr>
 * </table>
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

    /** 租约超时未提交（L17 Worker 标记），或机娘自报失败（L18）。主人可以 retry 它。 */
    FAILED_TIMEOUT("FAILED_TIMEOUT"),

    /**
     * 主人结束协作，这个席位不再需要了（L18 新增，V015）。
     *
     * <p>★ 它存在的理由不是「状态机好看」，而是<b>让还在轮询的机娘停下来</b>：
     * 收工后若不改票状态，第 3 棒的机娘跑着 {@code collab wait}，查到的票仍是
     * {@code WAITING_PREDECESSOR}，服务端会答「5 秒后再来问」——
     * <b>它在等一个永远不会到来的信号，直到 900 秒超时退出</b>。
     * 那句「前一棒尚未完成」是真话，但真相是「协作已经收工了，你可以走了」。
     *
     * <p>★ 为什么不靠 session 的终态兜底：那要求<b>每一个读票的地方都记得 join session</b>，
     * 而每写一个新查询都可能忘记。同 L15 判据——
     * <b>不变量由数据的存在性保证，不由每个查询记得过滤来保证。</b>
     *
     * <p>★ 为什么叫 CANCELLED 而不是 REVOKED（attempt / handoff 用的那个词）：
     * REVOKED 是吊销一张<b>凭证</b>（「这东西作废了」）；CANCELLED 是取消一个<b>席位</b>
     * （「这个位置不再需要有人来坐」）。票不是凭证，是队列里的位置。
     */
    CANCELLED("CANCELLED");

    private final String code;

    TicketStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
