package com.agentlog.collaboration.application;

import com.agentlog.collaboration.api.dto.response.TicketStatusView;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionAttemptDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionAttemptMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionTicketMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.security.AgentIdentity;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 查席位状态（L16 · {@code GET /agent/contribution-tickets/{ticketCode}}）。
 *
 * <p>这是 CLI {@code collab wait} 轮询的那个端点，也是本课三个端点里唯一的只读操作。
 *
 * <h3>它的两个用途</h3>
 * <ol>
 *   <li><b>等前序</b>：第二个机娘在 L15 的 join 那一刻就拿到了票，但状态是 WAITING_PREDECESSOR。
 *       它靠轮询这里，等前一棒 submit 把自己唤醒成 READY_TO_WRITE。</li>
 *   <li><b>幂等窗口的自愈</b>：submit 的业务已提交、台账却没来得及落 COMPLETED 时，
 *       客户端重试会拿到 409「处理中」。这时查这里 —— 席位是 DONE 就说明上次其实成功了。
 *       （见 {@code IdempotencyStore#complete} 的窗口说明。）</li>
 * </ol>
 */
@Service
public class TicketStatusService {

    private final ContributionTicketMapper ticketMapper;
    private final CollaborationSessionMapper sessionMapper;
    private final ContributionAttemptMapper attemptMapper;

    public TicketStatusService(ContributionTicketMapper ticketMapper,
                               CollaborationSessionMapper sessionMapper,
                               ContributionAttemptMapper attemptMapper) {
        this.ticketMapper = ticketMapper;
        this.sessionMapper = sessionMapper;
        this.attemptMapper = attemptMapper;
    }

    @Transactional(readOnly = true)
    public TicketStatusView get(AgentIdentity principal, String ticketCode) {
        ContributionTicketDO ticket = ticketMapper.selectByCode(ticketCode);
        if (ticket == null) {
            throw new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }
        // 多租户行级授权：席位本身没有 owner 列，归属挂在会话上，所以要回查一次会话。
        // 跨主人一律 404（不是 403）—— 403 等于承认"它存在、只是不给你"，会泄漏资源存在性。
        CollaborationSessionDO session = sessionMapper.selectById(ticket.getSessionId());
        if (session == null || !Objects.equals(session.getOwnerUserId(), principal.ownerUserId())) {
            throw new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }
        // ★ 注意这里【不校验 requiredAgentId】：同一个主人名下的机娘可以互相看见对方的席位状态。
        //   「谁能写」由 claim lease 的闸门把关，「谁能看」按租户划 —— 两件事，别混。
        //   若这里也按机娘过滤，机娘就无法通过轮询得知"前一棒（别的机娘）写完没有"，wait 直接失效。

        // L18 补：带上最近一次尝试的 attemptNo 与 errorReportId。
        //   errorReportId 从 L16 起就承诺「L17 会填」，直到本课才真正填上 ——
        //   在此之前机娘看到 FAILED_TIMEOUT 却拿不到事故报告指针，读不到 suggested_actions_json，
        //   CLI 侧的自愈引导是断的。
        ContributionAttemptDO latest = attemptMapper.selectLatestByTicket(ticket.getId());
        return latest == null
                ? TicketStatusView.from(ticket)
                : TicketStatusView.from(ticket, latest.getAttemptNo(), latest.getErrorReportId());
    }
}
