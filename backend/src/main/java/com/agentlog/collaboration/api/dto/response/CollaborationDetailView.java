package com.agentlog.collaboration.api.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * 协作详情（L18 时间线页的全部数据）。对齐活契约 {@code CollaborationView}。
 *
 * <h3>★ 它是 {@code collaboration_session.status} 的【第一个真正消费者】</h3>
 * 核实过：在 L18 之前，session 的状态被<b>写 4 处、读来做判定 0 处</b>，
 * 没有任何 SQL 的 WHERE 用到它。这正是 L16 那个 bug
 * （{@code AWAITING_CONTINUATION → RUNNING} 从没实现）能潜伏两课的原因——
 * <b>一个从来没被读过的状态，写错了也没人会发现</b>。
 * <p>L17 说「状态不准是会骗人的」，这一页补上了更狠的后半截：
 * <b>没人读的状态连骗人的机会都没有，它只是静静地错着——直到有人把它显示出来。</b>
 *
 * <h3>★ 三段数据，回答三个不同的问题</h3>
 * <pre>
 *   tickets  →「现在这条链是什么局面」   （每个席位 + 它的每一次尝试）
 *   timeline →「一路上发生了什么」       （audit_record，按时间升序）
 *   errors   →「出了什么事、该怎么办」   （error_report + 建议动作）
 * </pre>
 *
 * @param draftId 首棒成功后才有。null = 还没有任何内容（首棒未完成或已作废）
 */
public record CollaborationDetailView(
        String postTicket,
        String status,
        String plannedTitle,
        String plannedSummary,
        Long draftId,
        int lastCompletedSequence,
        Instant createdAt,
        Instant updatedAt,
        List<TicketDetail> tickets,
        List<TimelineEntry> timeline,
        List<ErrorDetail> errors
) {

    /**
     * 一个席位的全貌。
     *
     * @param attempts 这张票的每一次尝试。★ retry 过的票会有<b>多条</b>
     *                 （attempt_no = 1 超时失败、2 重来一次），<b>旧的原样保留</b>——
     *                 这就是验收栏「错误历史保留」在界面上的样子
     */
    public record TicketDetail(
            String ticketCode,
            int sequenceNo,
            String status,
            Long requiredAgentId,
            String requiredAgentNickname,
            List<AttemptDetail> attempts
    ) {}

    /**
     * 一次写作尝试。
     *
     * <p>★ 没有 {@code leaseToken}：租约摘要是凭证，不是展示数据，绝不进任何读模型。
     */
    public record AttemptDetail(
            int attemptNo,
            String status,
            Instant startedAt,
            Instant finishedAt,
            Instant leaseExpiresAt,
            Long errorReportId
    ) {}

    /** 时间线上的一条流水。{@code audit_record} 的可读形态。 */
    public record TimelineEntry(
            Long id,
            String actionType,
            String summary,
            String agentNickname,
            Instant at
    ) {}

    /**
     * 一次事故 + 系统给的建议动作。
     *
     * @param suggestedActions ★ <b>前端的按钮由它驱动，不写死</b>。
     *                         L17 写入时已按首棒/中间棒给了不同建议：
     *                         首棒 {@code ["TERMINATE_SESSION"]}（没东西可救）、
     *                         中间棒 {@code ["RETRY_TICKET","TERMINATE_SESSION"]}。
     *                         <p>为什么不写死：按钮写死意味着「什么时候能 retry」这条规则
     *                         <b>存在两份</b>（前端一份、后端一份），而两份规则一定会漂移。
     *                         让服务端说了算，前端只负责渲染。
     */
    public record ErrorDetail(
            Long id,
            String ticketCode,
            String errorType,
            String failedStage,
            String summary,
            List<String> suggestedActions,
            Instant createdAt
    ) {}
}
