package com.agentlog.collaboration.application;

import com.agentlog.collaboration.api.dto.response.PrecedingContentView;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.PrecedingBlockRow;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionTicketMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.PrecedingContentMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.security.AgentIdentity;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写作前文查询（L18 补）——「我这一棒之前，这篇文章已经长成什么样」。
 *
 * <h3>★ 这个端点补的缺口，是主人在 L18 验收时问出来的</h3>
 * 他在等第 2 棒超时的间隙注意到：{@code claim lease} 返回的 {@code writingContext} 里
 * 只有会话票、标题、摘要，<b>那之前的正文怎么传给后续接力的机娘？</b>
 * <p>核实结果：机娘侧 7 个端点<b>没有一个</b>能读到。
 * {@code ClaimLeaseService} 的注释写着「草稿 URL 由主人给，机娘无读权」——
 * 也就是<b>靠主人手动复制粘贴</b>。这与「主人是唯一的传递媒介」一脉相承，
 * 但代价没人算过：接力棒 51 字符复制一次不痛，<b>正文几百上千字、每棒都要复制一次</b>。
 *
 * <h3>★ 权限边界：按【租户】划，不按【机娘】划</h3>
 * 沿用 L16 {@code TicketStatusService} 已确立的判据：
 * <blockquote>「谁能<b>写</b>」由 claim lease 的闸门把关；「谁能<b>看</b>」按租户划——两件事，别混。</blockquote>
 * 若按机娘过滤，第 3 棒就读不到第 2 棒写的东西，<b>这个端点会直接失效</b>。
 * 同一主人名下的机娘本来就彼此可见（都是他派出去的）。
 *
 * <h3>★ 「只能读已完成的」不需要写任何过滤条件</h3>
 * {@code draft_block} 里只会有<b>已 submit</b> 的内容（submit 才写块）。
 * 因果链的串行性已经保证了「我后面的还没写」——
 * <b>不变量由数据的存在性保证，不由查询记得过滤来保证</b>（L15 判据的又一次兑现）。
 */
@Service
public class PrecedingContentService {

    /**
     * 块数上限。<b>不做分页</b>——一篇协作文章的块数是个位数，分页是过度设计；
     * 但没有上限就是没有防线（谁也不知道将来会不会有人排 200 棒）。
     */
    private static final int MAX_BLOCKS = 50;

    private final ContributionTicketMapper ticketMapper;
    private final CollaborationSessionMapper sessionMapper;
    private final PrecedingContentMapper contentMapper;

    public PrecedingContentService(ContributionTicketMapper ticketMapper,
                                   CollaborationSessionMapper sessionMapper,
                                   PrecedingContentMapper contentMapper) {
        this.ticketMapper = ticketMapper;
        this.sessionMapper = sessionMapper;
        this.contentMapper = contentMapper;
    }

    @Transactional(readOnly = true)
    public PrecedingContentView get(AgentIdentity principal, String ticketCode) {
        ContributionTicketDO ticket = ticketMapper.selectByCode(ticketCode);
        if (ticket == null) {
            throw new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }
        CollaborationSessionDO session = sessionMapper.selectById(ticket.getSessionId());
        // 多租户行级授权：不是我主人的协作 → 一律 404（不是 403，避免泄漏资源存在性）。
        if (session == null || !Objects.equals(session.getOwnerUserId(), principal.ownerUserId())) {
            throw new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }

        // 首棒（或首棒还没提交）：draft 尚不存在，正常返回空——不是错误。
        // ★ 这正是「首棒失败不暴露空草稿」的另一面：草稿不存在时，读路径查不到任何东西，
        //   不需要任何 if 去"藏"它。
        if (session.getDraftId() == null) {
            return new PrecedingContentView(session.getPostTicket(), session.getPlannedTitle(),
                    session.getPlannedSummary(), ticket.getSequenceNo(), 0, false, List.of());
        }

        // 多取一块用来判断是否被截断 —— ★ 宁可如实说"截断了"，也不要静悄悄少给：
        //   沉默的截断会让机娘以为自己读到了全文，然后基于残缺的上下文续写。
        List<PrecedingBlockRow> rows = contentMapper.selectVisibleBlocks(
                session.getDraftId(), MAX_BLOCKS + 1);
        boolean truncated = rows.size() > MAX_BLOCKS;
        if (truncated) {
            rows = rows.subList(0, MAX_BLOCKS);
        }

        List<PrecedingContentView.PrecedingBlockView> blocks = rows.stream()
                .map(r -> new PrecedingContentView.PrecedingBlockView(
                        r.getDisplayOrder(), r.getAuthorType(),
                        r.getAuthorName(), r.getSourceTool(), r.getContent()))
                .toList();

        return new PrecedingContentView(session.getPostTicket(), session.getPlannedTitle(),
                session.getPlannedSummary(), ticket.getSequenceNo(),
                blocks.size(), truncated, blocks);
    }
}
