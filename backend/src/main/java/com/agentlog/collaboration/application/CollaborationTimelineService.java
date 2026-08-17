package com.agentlog.collaboration.application;

import com.agentlog.collaboration.api.dto.response.CollaborationDetailView;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.TimelineAttemptRow;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.TimelineAuditRow;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.TimelineErrorRow;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.TimelineTicketRow;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.OwnerCollaborationMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 协作时间线查询（L18）——本课<b>唯一的只读端点</b>，也是整个协作页面的数据来源。
 *
 * <h3>★ 为什么这里【不开 Facade】，直接用跨模块只读 SQL</h3>
 * 本服务要读三个模块的表：{@code audit_record}（audit）、{@code error_report}（reliability）、
 * {@code agent_account}（identity），而它自己在 collaboration。
 * L16 建 {@code ContentFacade}、L17 建 {@code CollaborationFacade} 都是为了跨模块，
 * 为什么这次不建？
 *
 * <p>项目从 D-05 起的分工是<b>按「读/写/派生」分的，不是按「跨不跨模块」分的</b>：
 * <table border="1">
 *   <tr><th></th><th>怎么跨模块</th><th>先例</th></tr>
 *   <tr><td><b>读</b></td><td>只读 SQL 投影</td><td>L14 跨模块查作者</td></tr>
 *   <tr><td><b>写</b></td><td>Facade</td><td>L16 ContentFacade、L17 CollaborationFacade</td></tr>
 *   <tr><td><b>派生行为</b></td><td>领域事件</td><td>L17 写 error_report / audit_record</td></tr>
 * </table>
 * <b>Facade 存在的理由是「写要保证事务边界与不变量」</b>
 * （{@code appendAgentContribution} 用 {@code Propagation.MANDATORY} 强制必须在事务里）。
 * <b>读没有这个问题</b>——读不改变任何东西，读错了最多显示错，不会写坏数据。
 * 为只读架一层 Facade 是拿成本换不存在的收益。
 *
 * <p>⚠️ 而领域事件在这里<b>根本用不了</b>：事件是「写」的派生机制（我做完了 → 喊一声 → 谁关心谁去做），
 * 而时间线要<b>现在立刻</b>拿到 20 条数据渲染页面。你没法"发个事件然后等它把数据告诉你"。
 *
 * <p>⚠️ 代价诚实说：这几条 SQL 与别的模块的表结构<b>隐式耦合</b>了，编译器不会替你检查——
 * 对方改列名，这里运行时才炸。项目接受这个代价（D-05）。
 *
 * <h3>★ 为什么在内存里组装而不是一条大 SQL join 出来</h3>
 * 票 : 尝试 = 1:N，若 join 成一张宽表，票的字段会随尝试次数<b>重复 N 遍</b>（笛卡尔膨胀），
 * 还要在 Java 里去重。分两次查、按 {@code ticketId} 在内存里归并，SQL 更简单、语义更清楚。
 * 数据量也支持这么做：一条协作的席位是个位数。
 */
@Service
public class CollaborationTimelineService {

    private static final Logger log = LoggerFactory.getLogger(CollaborationTimelineService.class);

    private static final TypeReference<List<String>> ACTION_LIST = new TypeReference<>() {};

    private final OwnerCollaborationMapper ownerMapper;
    private final CollaborationSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    public CollaborationTimelineService(OwnerCollaborationMapper ownerMapper,
                                        CollaborationSessionMapper sessionMapper,
                                        ObjectMapper objectMapper) {
        this.ownerMapper = ownerMapper;
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CollaborationDetailView get(Long ownerUserId, String postTicket) {
        // 行级授权写进 SQL 的 WHERE：不是你的 → 查不到 → 404（不是 403，避免泄漏资源存在性）。
        Long sessionId = ownerMapper.selectSessionIdByPostTicket(postTicket, ownerUserId);
        if (sessionId == null) {
            throw new ApiException(ApiStatus.ACPP_SESSION_NOT_FOUND);
        }
        CollaborationSessionDO session = sessionMapper.selectById(sessionId);

        List<TimelineTicketRow> ticketRows = ownerMapper.selectTicketTimeline(sessionId);
        List<TimelineAttemptRow> attemptRows = ownerMapper.selectAttemptTimeline(sessionId);
        List<TimelineAuditRow> auditRows = ownerMapper.selectAuditTimeline(sessionId);
        List<TimelineErrorRow> errorRows = ownerMapper.selectErrorTimeline(sessionId);

        // 尝试按票分组（票 : 尝试 = 1:N）。
        Map<Long, List<CollaborationDetailView.AttemptDetail>> attemptsByTicket = new HashMap<>();
        for (TimelineAttemptRow a : attemptRows) {
            attemptsByTicket
                    .computeIfAbsent(a.getTicketId(), k -> new ArrayList<>())
                    .add(new CollaborationDetailView.AttemptDetail(
                            a.getAttemptNo(), a.getStatus(),
                            a.getStartedAt(), a.getFinishedAt(),
                            a.getLeaseExpiresAt(), a.getErrorReportId()));
        }

        // ticketId → ticketCode：错误报告里存的是内部 id，但对外一律只暴露 CT- 编码
        //（同 session 只暴露 PT-）——自增 id 出现在响应里就等于给了枚举者一把梯子。
        Map<Long, String> codeByTicketId = new HashMap<>();
        List<CollaborationDetailView.TicketDetail> tickets = new ArrayList<>(ticketRows.size());
        for (TimelineTicketRow t : ticketRows) {
            codeByTicketId.put(t.getTicketId(), t.getTicketCode());
            tickets.add(new CollaborationDetailView.TicketDetail(
                    t.getTicketCode(), t.getSequenceNo(), t.getStatus(),
                    t.getRequiredAgentId(), t.getRequiredAgentNickname(),
                    attemptsByTicket.getOrDefault(t.getTicketId(), List.of())));
        }

        List<CollaborationDetailView.TimelineEntry> timeline = new ArrayList<>(auditRows.size());
        for (TimelineAuditRow r : auditRows) {
            timeline.add(new CollaborationDetailView.TimelineEntry(
                    r.getId(), r.getActionType(), r.getSummary(),
                    r.getAgentNickname(), r.getCreatedAt()));
        }

        List<CollaborationDetailView.ErrorDetail> errors = new ArrayList<>(errorRows.size());
        for (TimelineErrorRow e : errorRows) {
            errors.add(new CollaborationDetailView.ErrorDetail(
                    e.getId(), codeByTicketId.get(e.getTicketId()),
                    e.getErrorType(), e.getFailedStage(), e.getSummary(),
                    parseActions(e.getSuggestedActionsJson()), e.getCreatedAt()));
        }

        return new CollaborationDetailView(
                session.getPostTicket(), session.getStatus(),
                session.getPlannedTitle(), session.getPlannedSummary(),
                session.getDraftId(), session.getLastCompletedSequence(),
                session.getCreatedAt(), session.getUpdatedAt(),
                tickets, timeline, errors);
    }

    /**
     * 把 {@code suggested_actions_json} 解析成动作列表。
     *
     * <p>★ 解析失败<b>不抛异常，返回空列表</b>：时间线是<b>诊断页面</b>——
     * 它存在的全部意义就是「出事的时候给主人看」。若因为一条脏 JSON 就整页 500，
     * 恰恰在最需要它的时刻失效了。
     * <p>判据：<b>诊断工具的可用性优先级高于它自身的严格性。</b>
     */
    private List<String> parseActions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, ACTION_LIST);
        } catch (Exception e) {
            log.warn("suggested_actions_json 解析失败，按空列表处理：{}", json, e);
            return List.of();
        }
    }
}
