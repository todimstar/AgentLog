package com.agentlog.collaboration.api.dto.response;

import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;

/**
 * 席位视图（L15）。对齐活契约 TicketView。
 *
 * 只暴露对外标识 ticketCode，**不暴露自增 id**——同 postTicket 的理由：
 * 出现在 API/URL 里的标识必须不可枚举。
 */
public record TicketView(
        String ticketCode,
        int sequenceNo,
        String status,
        Long requiredAgentId
) {

    public static TicketView from(ContributionTicketDO ticket) {
        return new TicketView(
                ticket.getTicketCode(),
                ticket.getSequenceNo(),
                ticket.getStatus(),
                ticket.getRequiredAgentId());
    }
}
