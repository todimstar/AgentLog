package com.agentlog.reliability.application;

import com.agentlog.reliability.infrastructure.persistence.dataobject.ErrorReportDO;
import com.agentlog.reliability.infrastructure.persistence.mapper.ErrorReportMapper;
import com.agentlog.shared.event.AttemptExpired;
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
    public void onAttemptExpired(AttemptExpired e) {
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
}
