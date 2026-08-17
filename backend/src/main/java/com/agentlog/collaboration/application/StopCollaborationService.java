package com.agentlog.collaboration.application;

import com.agentlog.collaboration.domain.SessionStatus;
import com.agentlog.collaboration.infrastructure.persistence.mapper.OwnerCollaborationMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.event.CollaborationStopped;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主人结束协作（L18）——协作的<b>唯一出口</b>，把控制权还给人。
 *
 * <h3>★ 它的本质是「解锁」，不是「放弃」</h3>
 * 协作还开着的时候草稿是<b>只读</b>的（{@code 06-web/owner-review-ux.md} 第 4 行「草稿只读」，
 * 错误码 {@code DRAFT_LOCKED_BY_COLLAB} 的自愈动作正是「等待或 terminate」）。
 * 只要协作不结束，<b>主人就永远不能编辑、也不能发布那篇文章</b>。
 * <p>⚠️ 它<b>不删任何内容</b>：{@code post / draft / draft_block / contribution} 四张表一行都不动。
 * 已经写好的每一棒原封不动躺在草稿里。
 *
 * <h3>★ 为什么只有一个出口（主人 2026-08-15 的产品判断，推翻了蓝图）</h3>
 * 蓝图状态机画了两条出边：{@code AWAITING_CONTINUATION → READY_FOR_OWNER_REVIEW}（正常收工）
 * 与 {@code PAUSED_ON_ERROR → TERMINATED}（出错放弃）。主人的论证：
 * <blockquote>
 * 两者对<b>草稿</b>而言结果完全相同——协作不再占着它。至于内容是删是留，
 * 那是草稿模块（L19）与删除功能的事，<b>不该由 terminate 回答</b>。
 * </blockquote>
 * ★ 判据：<b>别让一个机制回答两个问题。</b>（与他在 L16 否决 handoff TTL 是同一条。）
 * <p>额外收益：主人少做一次选择，而那次选择他<b>此刻根本没法做</b>——他还没看内容呢。
 * <p>首棒失败那条路不归它管：L17 已自动落 {@code INVALIDATED}，
 * 那时 post/draft 从未创建，<b>没有草稿要解锁，主人不需要按任何按钮</b>。
 *
 * <h3>★ 执行顺序：一道闸门 + 四步收尾</h3>
 * <pre>
 *   ① 查会话 + 行级授权
 *   ② 记下"谁正在写"（只为审计，判定不靠它）
 *   ③ ★闸门★ 会话 → READY_FOR_OWNER_REVIEW
 *   ④ 进行中的 attempt → REVOKED     （它 submit 会撞闸门吃 409）
 *   ⑤ 未完成的票 → CANCELLED         （让还在轮询的机娘停下来）
 *   ⑥ 尾令牌 → REVOKED               （不能再有新人进来）
 *   ⑦ 按事实重算 last_completed_sequence
 *   ⑧ 发 CollaborationStopped 事件
 * </pre>
 * ④⑤⑥ 一次性用上了三个至今没有任何代码产生过的状态值：
 * {@code Attempt.REVOKED}、{@code Ticket.CANCELLED}（V015 新增）、{@code Handoff.REVOKED}。
 */
@Service
public class StopCollaborationService {

    private static final Logger log = LoggerFactory.getLogger(StopCollaborationService.class);

    private final OwnerCollaborationMapper ownerMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public StopCollaborationService(OwnerCollaborationMapper ownerMapper,
                                    ApplicationEventPublisher events,
                                    Clock clock) {
        this.ownerMapper = ownerMapper;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public StopOutcome stop(Long ownerUserId, String postTicket) {
        Instant now = Instant.now(clock);

        // ① 行级授权写进 SQL：不是你的 → 查不到 → 404（不是 403，避免泄漏资源存在性）。
        Long sessionId = ownerMapper.selectSessionIdByPostTicket(postTicket, ownerUserId);
        if (sessionId == null) {
            throw new ApiException(ApiStatus.ACPP_SESSION_NOT_FOUND);
        }

        // ② 先记下"此刻谁在写"。★ 这只是为了写审计，不参与任何判定——
        //    判定完全交给 ④ 那条条件 UPDATE（否则又是「查-判-改」三步走）。
        Long activeAttemptId = ownerMapper.selectActiveAttemptId(sessionId);

        // ③ ★闸门★ 只有"还没结束"的协作能被结束。连点两次，第二次落 0 行。
        if (ownerMapper.stopSession(sessionId, now) == 0) {
            throw new ApiException(ApiStatus.ACPP_SESSION_NOT_ACTIVE);
        }

        // ④ 吊销进行中的 attempt。★★ 本课唯一的真竞态就在这一行上 ★★
        //    主人按下按钮的同一毫秒，机娘正好 submit 成功：两条 UPDATE 争同一行，
        //    MySQL 行锁让它们串行，谁先谁赢，【两边都不会出现半截状态】：
        //      机娘赢 → 这里影响 0 行；它的票已是 DONE，⑤ 的 WHERE 也排除了 DONE
        //               ⇒ ★ 那一棒的内容【保住了】——它确实在按钮按下之前写完了。
        //      我们赢 → attempt=REVOKED，它的 submit 闸门 WHERE status='ACTIVE' 返回 0 → 干净的 409。
        int revokedAttempts = ownerMapper.revokeActiveAttempts(sessionId, now);

        // ⑤ 取消所有未完成的席位。理由是【让还在轮询的机娘停下来】，不是"状态机好看"。
        int cancelledTickets = ownerMapper.cancelUnfinishedTickets(sessionId, now);

        // ⑥ 吊销尾令牌：协作结束了，不能再有人进来。
        boolean handoffRevoked = ownerMapper.revokeTailToken(sessionId) > 0;

        // ⑦ 按事实重算「已完成到第几棒」。
        //    ★ 为什么需要这一步：session DO 带 @Version，submit 走 updateById 是乐观锁；
        //      若本次 stop 先提交，那边的 updateById 会【静默影响 0 行】，
        //      内容与票都落对了，但 last_completed_sequence 没跟上。
        //    ★ 判据：能从事实推导出来的派生值，就不要依赖执行顺序去维护它。
        //      这一步只问"哪些票真的 DONE 了"，不问"谁先谁后"，所以幂等且必然正确。
        ownerMapper.recalcLastCompletedSequence(sessionId, now);

        events.publishEvent(new CollaborationStopped(
                sessionId, ownerUserId,
                revokedAttempts > 0 ? activeAttemptId : null,
                cancelledTickets, handoffRevoked, now));

        log.info("主人 {} 结束了协作 {}：吊销进行中尝试 {} 条、取消席位 {} 张、尾令牌吊销={}",
                ownerUserId, postTicket, revokedAttempts, cancelledTickets, handoffRevoked);

        return new StopOutcome(postTicket, SessionStatus.READY_FOR_OWNER_REVIEW.getCode(),
                cancelledTickets, revokedAttempts > 0, handoffRevoked);
    }

    public record StopOutcome(String postTicket,
                              String sessionStatus,
                              int cancelledTicketCount,
                              boolean revokedRunningAttempt,
                              boolean handoffRevoked) {
    }
}
