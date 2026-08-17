package com.agentlog.reliability.application;

import com.agentlog.reliability.infrastructure.persistence.dataobject.ErrorReportDO;
import com.agentlog.reliability.infrastructure.persistence.mapper.ErrorReportMapper;
import com.agentlog.shared.event.AttemptExpired;
import com.agentlog.shared.event.AttemptFailedByClient;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * 事故报告的记录者（L17）。监听 {@link AttemptExpired}，写一行 {@code error_report}。
 *
 * <h3>★ 为什么是「监听事件」而不是「被 Worker 直接调用」</h3>
 * 一棒超时之后要顺带做的事不止一件：写事故报告（本类）、写审计流水（audit 模块）、
 * 将来还要通知主人（notification，L22）。若让 Worker 逐个直接调，它会越来越胖、
 * 而且<b>每加一个订阅者就得改它一次</b>。
 * <p>用事件之后，Worker 只喊一声，谁关心谁自己订阅——加第四个订阅者时 Worker 一个字都不用改。
 *
 * <h3>★ {@code @ApplicationModuleListener} 比普通 {@code @EventListener} 多给了什么</h3>
 * 它等价于 {@code @Async + @Transactional(REQUIRES_NEW) + 事务性发件箱}：
 * 事件在<b>发布方的业务事务里</b>先写进 {@code EVENT_PUBLICATION} 表，本监听器成功后标记完成、
 * 失败则留存等重投。于是<b>「业务成功了，事故报告一定不会丢」</b>。
 * <p>普通 Spring 事件做不到这点——监听器抛异常，事件就没了。
 *
 * <h3>★ 边界（Pack 10-reliability/modulith-events.md 划的线）</h3>
 * 事件只做<b>派生行为</b>；<b>不变量永远由 MySQL 状态机守</b>。
 * 所以 attempt/ticket/session 的状态推进全部在 collaboration 的业务事务里<b>同步</b>完成，
 * 不经过事件——异步的东西不能用来守不变量。
 */
@Service
public class ExpireAttemptService {

    private static final Logger log = LoggerFactory.getLogger(ExpireAttemptService.class);

    private final ErrorReportMapper errorReportMapper;
    private final Clock clock;

    public ExpireAttemptService(ErrorReportMapper errorReportMapper, Clock clock) {
        this.errorReportMapper = errorReportMapper;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void onAttemptExpired(AttemptExpired e) {//整个函数就是更新error表和关联外键的attempt表中字段
        Instant now = Instant.now(clock);

        ErrorReportDO r = new ErrorReportDO();
        r.setOwnerUserId(e.ownerUserId());
        r.setSessionId(e.sessionId());
        r.setTicketId(e.ticketId());
        r.setAttemptId(e.attemptId());
        r.setAgentId(e.agentId());
        r.setErrorType("LEASE_TIMEOUT");
        r.setFailedStage("AWAITING_SUBMISSION");
        r.setSummary(String.format("第 %d 棒的机娘租约超时，未能在有效期内提交内容", e.sequenceNo()));
        // ★ 建议动作因「首棒 / 中间棒」而不同：
        //   首棒失败 → post/draft 从未创建，没东西可救，只能终止；
        //   中间棒失败 → 草稿还在，可以让原机娘换个新对话 retry（L18）。
        r.setSuggestedActionsJson(e.firstTurn()
                ? "[\"TERMINATE_SESSION\"]"
                : "[\"RETRY_TICKET\", \"TERMINATE_SESSION\"]");
        r.setCreatedAt(now);
        errorReportMapper.insert(r);

        // 回填 attempt.error_report_id（V014 补的那条外键就是为这一步）
        errorReportMapper.linkAttemptToErrorReport(e.attemptId(), r.getId(), now);

        log.info("[ErrorReport] attempt {} 已记录事故报告 #{}", e.attemptId(), r.getId());
    }

    /**
     * 机娘<b>自报</b>失败的事故报告（L18）。
     *
     * <h3>★ 与超时那条路径的关键区别：{@code summary} 记的是【真实原因】</h3>
     * 超时那条只能写「租约超时，未能在有效期内提交内容」——那是<b>症状</b>，不是原因：
     * 服务端根本不知道机娘为什么没回来（对话崩了？需求不清？工具报错？）。
     * <p>自报失败则带着机娘自己说的理由。对主人而言，<b>「上一棒内容缺了关键信息」
     * 比「租约超时」有用一百倍</b>——前者他能立刻决定怎么办，后者他只能猜。
     *
     * <p>★ 这也是为什么值得单开一个端点：省下的 15 分钟等待是次要的，
     * <b>把「症状」换成「原因」才是主要的</b>。
     */
    @ApplicationModuleListener
    public void onAttemptFailedByClient(AttemptFailedByClient e) {
        Instant now = Instant.now(clock);

        ErrorReportDO r = new ErrorReportDO();
        r.setOwnerUserId(e.ownerUserId());
        r.setSessionId(e.sessionId());
        r.setTicketId(e.ticketId());
        r.setAttemptId(e.attemptId());
        r.setAgentId(e.agentId());
        r.setErrorType("CLIENT_REPORTED_FAILURE");
        r.setFailedStage("WRITING");
        r.setSummary(String.format("第 %d 棒的机娘主动报告失败：%s",
                e.sequenceNo(), e.reason() == null || e.reason().isBlank() ? "（未说明原因）" : e.reason()));
        // 建议动作的分叉与超时那条完全一致 —— 判据不是「怎么失败的」，而是【有没有草稿可救】：
        //   首棒失败 → post/draft 从未创建，只能结束协作；
        //   中间棒   → 草稿还在，可以让原机娘换个新对话 retry。
        r.setSuggestedActionsJson(e.firstTurn()
                ? "[\"TERMINATE_SESSION\"]"
                : "[\"RETRY_TICKET\", \"TERMINATE_SESSION\"]");
        r.setCreatedAt(now);
        errorReportMapper.insert(r);

        errorReportMapper.linkAttemptToErrorReport(e.attemptId(), r.getId(), now);

        log.info("[ErrorReport] attempt {} 自报失败已记录事故报告 #{}", e.attemptId(), r.getId());
    }
}
