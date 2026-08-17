package com.agentlog.collaboration.api.dto.response;

import com.agentlog.collaboration.domain.TicketStatus;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;

/**
 * 席位状态视图（L16）。对齐活契约 TicketStatusView = TicketView + pollAfterSeconds + errorReportId。
 *
 * <h3>★ 为什么「等待」发生在客户端，而不是服务端长轮询</h3>
 * {@code course_schedule} 的 L16 scope 写着 {@code wait}，容易误读成要做一个长轮询端点。
 * 但契约里 L16 只有三个端点，没有 wait —— {@code 07-cli/cli-spec.md} 写的是
 * {@code agentlog collab wait --ticket CT-xxxx --timeout-seconds 900}，<b>wait 是 CLI 命令</b>。
 *
 * <p>服务端只回答「现在什么状态 + 建议多久后再问」，由客户端自己睡自己醒：
 * <ul>
 *   <li>服务端<b>不持有任何长连接</b>，也不占线程——一个机娘可能要等十几分钟，
 *       挂十几分钟的连接是把等待成本放在了最贵的一侧；</li>
 *   <li>节奏由<b>服务端掌握</b>（pollAfterSeconds），客户端不自作主张——
 *       将来要削峰、要退避，改服务端一处即可，不用推动所有客户端升级。</li>
 * </ul>
 *
 * @param pollAfterSeconds 建议隔多久再来问。0 = 别等了（要么轮到你了，要么等也没用）
 * @param errorReportId    失败详情指针。<b>L18 才真正填上</b>——见下方「补的旧账」
 * @param attemptNo        这张票已经尝试到第几次（从未尝试过则为 null）。
 *                         {@code > 1} 说明<b>之前失败过、是主人 retry 之后重开的</b>，
 *                         {@code collab resume} 据此告诉机娘「你在续摊，不是开新的」
 */
public record TicketStatusView(
        String ticketCode,
        int sequenceNo,
        String status,
        Long requiredAgentId,
        int pollAfterSeconds,
        Long errorReportId,
        Integer attemptNo
) {

    /** 还在排队时的建议轮询间隔（秒）。取值折中：太短空转烧配额，太长交接迟钝。 */
    private static final int POLL_INTERVAL_WHILE_WAITING = 5;

    /**
     * 不带 attempt 信息的简版（仅用于还没有任何尝试的席位）。
     *
     * <h3>🔴 L18 补的旧账：errorReportId 曾经恒为 null</h3>
     * L16 写下这一列时注释是「L17 才会有值，本课恒 null」，但 L17 结束了它<b>仍然硬编码 null</b>。
     * 后果：机娘 {@code collab status} 看到 {@code FAILED_TIMEOUT} 时拿不到事故报告指针，
     * 读不到 {@code suggested_actions_json}（"该怎么办"）——
     * {@code 08-skill/references/error-actions.md} 那套自愈机制在 CLI 侧<b>是断的</b>。
     *
     * <p>★ 它和另外两笔旧账（session 回不到 RUNNING、三个审计事件没人发）是同一个模式：
     * <b>注释是承诺，但没有任何机制保证它被兑现。</b>
     * 测试只测「代码做了什么」，测不出「代码答应了却没做什么」。
     */
    public static TicketStatusView from(ContributionTicketDO ticket) {
        return from(ticket, null, null);
    }

    /** 带上最近一次尝试的信息（L18）。{@code latestAttempt} 可为 null。 */
    public static TicketStatusView from(ContributionTicketDO ticket,
                                        Integer attemptNo,
                                        Long errorReportId) {
        return new TicketStatusView(
                ticket.getTicketCode(),
                ticket.getSequenceNo(),
                ticket.getStatus(),
                ticket.getRequiredAgentId(),
                pollAfterSecondsFor(ticket.getStatus()),
                errorReportId,
                attemptNo);
    }

    /**
     * 只有「等前序」这一种状态值得再问；其余都是 0：
     * READY_TO_WRITE 该立刻去领租约；LEASED/DONE 已成定局；
     * BLOCKED/FAILED 要人介入（主人 retry），机器再问一万次也不会变；
     * <b>CANCELLED（L18）主人已经收工</b>——这是本课新增这个状态值的<b>全部意义</b>：
     * 让还在轮询的机娘拿到 0，立刻停下来，而不是等一个永远不来的信号直到超时。
     */
    private static int pollAfterSecondsFor(String status) {
        return TicketStatus.WAITING_PREDECESSOR.getCode().equals(status)
                ? POLL_INTERVAL_WHILE_WAITING
                : 0;
    }
}
