package com.agentlog.collaboration.application;

import com.agentlog.collaboration.domain.SessionStatus;
import com.agentlog.collaboration.domain.TicketStatus;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionAttemptMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionTicketMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.OwnerCollaborationMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.event.TicketRetried;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主人 retry 一棒（L18）——<b>本项目第一个由「人的决策」驱动的状态推进</b>。
 *
 * <h3>★ 为什么这件事必须由人来做，机器不能自己做</h3>
 * L17 让秩序在崩溃后自愈了，但自愈的结果是<b>冻住</b>：票 FAILED_TIMEOUT、后序整条尾巴 BLOCKED、
 * 尾令牌 FROZEN、会话 PAUSED_ON_ERROR。L17 写的所有代码里<b>没有一行能让它重新动起来</b>。
 * <p>让 Worker 顺手重启行不行？不行，而且失败得很难看：
 * <pre>
 *   Worker 自动把票改回 READY_TO_WRITE → ……然后等谁来写？
 *   原机娘的对话已经崩了，它不存在了。服务端【没有任何办法】叫醒一个已关闭的 AI 对话。
 *   15 分钟后再超时 → 又自动重启 → 又没人来 → 死循环，而且每轮往 error_report 写一条。
 * </pre>
 * 根因：<b>服务端与机娘之间是「拉」不是「推」的关系</b>——机娘来问「轮到我了吗」，
 * 服务端只能回答；它<b>永远无法主动发起</b>一次机娘的写作，连对方还在不在都不知道。
 * <p>★ 判据（human-in-the-loop 的判定标准，不是「人比机器聪明」）：
 * <b>这一步需要的信息只存在于系统之外</b>——你还想不想继续写、能不能把那个 AI 叫回来。
 *
 * <h3>★ 执行顺序：一道闸门 + 三处解冻</h3>
 * <pre>
 *   ① 查会话与票（只取上下文 + 行级授权，【不做状态判定】）
 *   ② ★闸门★ 条件 UPDATE 票：FAILED_TIMEOUT → READY_TO_WRITE（连带校验会话仍在 PAUSED_ON_ERROR）
 *   ③ 解冻整条尾巴：BLOCKED_BY_PREDECESSOR → WAITING_PREDECESSOR（递归 CTE，走到底）
 *   ④ 解冻尾令牌：FROZEN → AVAILABLE
 *   ⑤ 会话：PAUSED_ON_ERROR → AWAITING_CONTINUATION
 *   ⑥ 发 TicketRetried 事件（审计 / 时间线）
 * </pre>
 *
 * <h3>★ 解冻不是冻结的倒放（本课最容易想错的地方）</h3>
 * L17 冻了五样，retry 只解冻其中三样，而且目标状态各不相同：
 * <table border="1">
 *   <tr><th>L17 冻的</th><th>retry 怎么解</th><th>为什么</th></tr>
 *   <tr><td>attempt → FAILED_TIMEOUT</td><td><b>不解冻，永久保留</b></td>
 *       <td>它是<b>历史</b>。这就是验收栏「错误历史保留」——新的尝试会以 attempt_no=2 另起一行</td></tr>
 *   <tr><td>死的那张票 → FAILED_TIMEOUT</td><td>→ READY_TO_WRITE</td><td>它要能被重新领</td></tr>
 *   <tr><td>后序整条尾巴 → BLOCKED</td><td>→ <b>WAITING_PREDECESSOR</b></td>
 *       <td>⚠️ <b>不是 READY_TO_WRITE</b>——前一棒还没重写完，后序凭什么能写</td></tr>
 *   <tr><td>尾令牌 → FROZEN</td><td>→ AVAILABLE</td><td>新人又能排队了</td></tr>
 *   <tr><td>会话 → PAUSED_ON_ERROR</td><td>→ AWAITING_CONTINUATION</td>
 *       <td>⚠️ <b>不是 RUNNING</b>——retry 那一刻没有任何人在写</td></tr>
 * </table>
 *
 * <h3>★ retry 不需要为「后面怎么继续」做任何准备</h3>
 * 等原机娘重新写完这一棒，L16 的 {@code wakeSuccessor} 会自动把下一棒唤醒成 READY_TO_WRITE。
 * <b>恢复之后的流转靠原来那套机制自己跑</b>——这正是状态机设计对了的标志。
 */
@Service
public class RetryTicketService {

    private static final Logger log = LoggerFactory.getLogger(RetryTicketService.class);

    private final OwnerCollaborationMapper ownerMapper;
    private final ContributionTicketMapper ticketMapper;
    private final ContributionAttemptMapper attemptMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RetryTicketService(OwnerCollaborationMapper ownerMapper,
                              ContributionTicketMapper ticketMapper,
                              ContributionAttemptMapper attemptMapper,
                              ApplicationEventPublisher events,
                              Clock clock) {
        this.ownerMapper = ownerMapper;
        this.ticketMapper = ticketMapper;
        this.attemptMapper = attemptMapper;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @param ownerUserId 当前登录主人（Chain 1 的 Session 派生，不由请求体传入）
     * @param postTicket  协作对外标识 {@code PT-<16hex>}
     * @param ticketCode  席位对外标识 {@code CT-<16hex>}
     */
    @Transactional
    public RetryOutcome retry(Long ownerUserId, String postTicket, String ticketCode) {
        Instant now = Instant.now(clock);

        // ① 取上下文 + 行级授权。授权写进 SQL 的 WHERE，越权直接变成"查不到"。
        Long sessionId = ownerMapper.selectSessionIdByPostTicket(postTicket, ownerUserId);
        if (sessionId == null) {
            throw new ApiException(ApiStatus.ACPP_SESSION_NOT_FOUND);
        }
        ContributionTicketDO ticket = ticketMapper.selectByCode(ticketCode);
        // ★ 路径里的 postTicket 不是装饰：这一行就是它的用途——校验「这张票确实属于这个会话」。
        //   一个不被检查的参数比没有这个参数更糟，它会制造「系统检查过了」的错觉。
        if (ticket == null || !Objects.equals(ticket.getSessionId(), sessionId)) {
            throw new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }

        // ② ★闸门★ —— 唯一的裁决者。它同时守住了三件事：票是超时态、协作仍在暂停态、这次没被重复执行。
        if (ownerMapper.retryTicket(ticketCode, now) == 0) {
            throw rejectionFor(ticketCode);
        }
        // ——— 走到这里，这次 retry 独占归我，下面没有竞争者 ———

        // ③ 解冻整条尾巴。0 也正常：retry 的可能就是链尾那一棒，后面本来没人。
        int unblocked = ownerMapper.unblockSuccessors(ticket.getId(), now);

        // ④ 解冻尾令牌。与③是两个正交维度：③管「已经进来的人能不能写」，④管「还能不能有新人进来」。
        boolean unfrozen = ownerMapper.unfreezeTailToken(sessionId) > 0;

        // ⑤ 会话回到「等人来接」。
        //    ★ 不是 RUNNING —— 此刻还没有任何人在写，原机娘得自己回来 claim-turn。
        //      状态要如实反映「有没有一棒正在进行」（L16 定的判据），否则时间线页会骗人。
        ownerMapper.resumeSessionAfterRetry(sessionId, now);

        // ⑥ 下一次会是第几次尝试。★ 这一行【此刻还不存在】——
        //    retry 只把票改回可写，attempt 由机娘 claim-turn 时才 MAX+1 建出来。
        Integer maxAttemptNo = attemptMapper.selectMaxAttemptNo(ticket.getId());
        int nextAttemptNo = (maxAttemptNo == null ? 0 : maxAttemptNo) + 1;

        events.publishEvent(new TicketRetried(
                sessionId, ownerUserId, ticket.getId(), ticket.getRequiredAgentId(),
                ticket.getSequenceNo(), nextAttemptNo, unblocked, unfrozen, now));

        log.info("主人 {} retry 了席位 {}（第 {} 棒），解冻后序 {} 张，尾令牌解冻={}",
                ownerUserId, ticketCode, ticket.getSequenceNo(), unblocked, unfrozen);

        return new RetryOutcome(ticketCode, TicketStatus.READY_TO_WRITE.getCode(),
                ticket.getRequiredAgentId(), ticket.getSequenceNo(), unblocked, unfrozen);
    }

    /**
     * 把「闸门返回 0 行」翻译成具体错误码。
     *
     * <p>★ 顺序不能反：<b>先原子改，改不动才回头问「为什么」</b>。
     * 反过来（先查状态再决定改不改）就又是「查-判-改」三步走，中间留出 TOCTOU 窗口。
     *
     * <p>⚠️ 回查读到的状态<b>本来就可能与失败的真实原因不同</b>（并发下读的是事务快照，
     * L16 被并发测试打红后写下的教训）。这不影响正确性——我们已经由 affectedRows 确定"你没改成"，
     * 回查只为给出更有帮助的提示。<b>绝不能反过来拿回查结果当判定依据。</b>
     */
    private ApiException rejectionFor(String ticketCode) {
        ContributionTicketDO current = ticketMapper.selectByCode(ticketCode);
        if (current == null) {
            return new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }
        if (!TicketStatus.FAILED_TIMEOUT.getCode().equals(current.getStatus())) {
            // 已被 retry 过（现在是 READY_TO_WRITE）、已经写完（DONE）、
            // 或者主人想跳过死棒去 retry 一张 BLOCKED 的票——都落在这里。
            return new ApiException(ApiStatus.ACPP_TICKET_NOT_RETRYABLE);
        }
        // 票确实是超时态，那闸门失败只可能是【会话已经不在 PAUSED_ON_ERROR】：
        // 要么首棒失败整个会话作废了，要么另一个标签页刚把协作结束掉。
        return new ApiException(ApiStatus.ACPP_SESSION_NOT_ACTIVE);
    }

    /**
     * retry 的结果。
     *
     * <h3>★ 这里【故意没有】nextAttemptNo（本课删掉的一个契约字段）</h3>
     * Pack 原始契约的 {@code RetryTicketResponse} 把 {@code attemptNo} 列为<b>必填</b>。
     * 那是蓝图 A 方案的遗留——A 方案里 retry 会真的建出那一行 attempt，返回它有意义。
     * <p>我们选了 B 方案（attempt 由 claim-turn 时才建），那一行<b>此刻并不存在</b>，
     * 返回一个预告值等于<b>为了兼容一个已经不成立的设计而保留一个没有消费者的字段</b>——
     * 而且叫 {@code attemptNo} 会让人以为库里已经有那一行了。
     * <p>★ 同一个字段在不同端点的价值可以完全相反：<b>真正需要 attemptNo 的是机娘侧的
     * {@code collab status}</b>（{@code >1} 说明"你在续摊，不是开新的"），那里它有真实的值。
     */
    public record RetryOutcome(String ticketCode,
                               String ticketStatus,
                               Long requiredAgentId,
                               Integer sequenceNo,
                               int unblockedCount,
                               boolean handoffUnfrozen) {
    }
}
