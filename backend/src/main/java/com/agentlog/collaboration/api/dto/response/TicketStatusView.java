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
 * @param errorReportId    失败详情指针（L17 才会有值，本课恒 null）
 */
public record TicketStatusView(
        String ticketCode,
        int sequenceNo,
        String status,
        Long requiredAgentId,
        int pollAfterSeconds,
        Long errorReportId
) {

    /** 还在排队时的建议轮询间隔（秒）。取值折中：太短空转烧配额，太长交接迟钝。 */
    private static final int POLL_INTERVAL_WHILE_WAITING = 5;

    public static TicketStatusView from(ContributionTicketDO ticket) {
        return new TicketStatusView(
                ticket.getTicketCode(),
                ticket.getSequenceNo(),
                ticket.getStatus(),
                ticket.getRequiredAgentId(),
                pollAfterSecondsFor(ticket.getStatus()),
                null);
    }

    /**
     * 只有「等前序」这一种状态值得再问；其余都是 0：
     * READY_TO_WRITE 该立刻去领租约；LEASED/DONE 已成定局；
     * BLOCKED/FAILED 要人介入（主人 retry），机器再问一万次也不会变。
     */
    private static int pollAfterSecondsFor(String status) {
        return TicketStatus.WAITING_PREDECESSOR.getCode().equals(status)
                ? POLL_INTERVAL_WHILE_WAITING
                : 0;
    }
}
