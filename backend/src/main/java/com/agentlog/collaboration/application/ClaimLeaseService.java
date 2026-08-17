package com.agentlog.collaboration.application;

import com.agentlog.collaboration.api.dto.response.ClaimLeaseResponse;
import com.agentlog.collaboration.domain.AttemptStatus;
import com.agentlog.collaboration.domain.SessionStatus;
import com.agentlog.collaboration.domain.TicketStatus;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionAttemptDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionAttemptMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionTicketMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.event.LeaseClaimed;
import com.agentlog.shared.security.AgentIdentity;
import com.agentlog.shared.security.TokenProperties;
import com.agentlog.shared.security.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 领租约：拿下这一棒的<b>独占写作权</b>，开出一次 Attempt（L16 · TX-04）。
 *
 * <h3>★ 执行顺序（全部难点都在这里）</h3>
 * <pre>
 *   ① 查票 + 查会话（只取上下文、做行级授权，<b>不做任何状态判定</b>）
 *   ② ★原子闸门 —— 唯一的裁决者，必须排在建 Attempt <b>之前</b>★
 *   ③ 建 Attempt + 签发租约（到这里已独占，零竞争）
 *   ④ 回填 ticket.active_attempt_id（按主键，无竞争）
 *   ⑤ 首棒时把会话推进 OPEN → RUNNING
 * </pre>
 *
 * <h3>★ ① 的 SELECT 会不会又变成「查-判-改」三步走</h3>
 * 不会，区别在<b>判定权归谁</b>：三步走的错在于「拿 SELECT 的结果做判定，然后 UPDATE 假设那个判定仍然成立」；
 * 这里的 SELECT 只是取数据（这张票属于哪个会话、会话是谁的），
 * 判定 100% 由 ② 那条 UPDATE 的 WHERE 完成。即使 ① 到 ② 之间票被别人领走，② 一定返回 0，我们照样失败。
 *
 * <h3>★ 为什么闸门必须在建 Attempt 之前（L15 那个坑的同构版）</h3>
 * 若顺序是「查票 → 建 Attempt → 改票状态」，两个线程会<b>双双先建 Attempt</b>——
 * 此时还没人被拦，attempt_no 都算成 1 → 第二个撞 {@code uk_attempt_ticket_no} 抛
 * DuplicateKeyException，败者拿到 500 而不是干净的 409 +「请查询席位状态」。
 * <p>更要命的是<b>覆盖面</b>：唯一键只挡得住「两个人抢同一张票」，
 * 挡不住「票根本不是你的 / 前序还没写完 / 协作已终止」——那些情形下没人跟你抢，
 * 票会建成功、返回 201。<b>靠唯一键兜底 = 用异常控制业务流程。</b>
 */
@Service
public class ClaimLeaseService {

    private final ContributionTicketMapper ticketMapper;
    private final ContributionAttemptMapper attemptMapper;
    private final CollaborationSessionMapper sessionMapper;
    private final TokenService tokenService;
    private final TokenProperties tokenProperties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ClaimLeaseService(ContributionTicketMapper ticketMapper,
                             ContributionAttemptMapper attemptMapper,
                             CollaborationSessionMapper sessionMapper,
                             TokenService tokenService,
                             TokenProperties tokenProperties,
                             ApplicationEventPublisher events,
                             Clock clock) {
        this.ticketMapper = ticketMapper;
        this.attemptMapper = attemptMapper;
        this.sessionMapper = sessionMapper;
        this.tokenService = tokenService;
        this.tokenProperties = tokenProperties;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public ClaimLeaseResponse claim(AgentIdentity principal, String ticketCode) {
        Instant now = Instant.now(clock);

        // ① 取上下文。这一步【不做状态判定】，判定全交给 ② 那条 UPDATE。
        ContributionTicketDO ticket = ticketMapper.selectByCode(ticketCode);
        if (ticket == null) {
            throw new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }
        CollaborationSessionDO session = sessionMapper.selectById(ticket.getSessionId());
        // 多租户行级授权：不属于我的主人 → 一律 404（不是 403），避免资源枚举。
        if (session == null || !Objects.equals(session.getOwnerUserId(), principal.ownerUserId())) {
            throw new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }

        // ② ★ 闸门：一条带条件的 UPDATE 完成 check + act。affectedRows 就是裁决书。
        int affected = ticketMapper.claimForLease(ticketCode, principal.agentAccountId(), now);
        if (affected == 0) {
            // 没领到。此时才回查判定【为什么】——先原子改、改不动才问原因，顺序反了就又是三步走。
            throw rejectionFor(ticketCode, principal);
        }
        // ——— 走到这里，这一棒已经独占归我。下面所有步骤没有任何竞争者。———

        // ③ 建 Attempt + 签发租约。
        // 「查最大值再 +1」在这里是安全的：票已被闸门锁成 LEASED，没有第二个线程能走到这一步。
        // 判据不是「用没用 SELECT」，而是「SELECT 到 INSERT 之间，别人还能不能插进来」。
        Integer maxAttemptNo = attemptMapper.selectMaxAttemptNo(ticket.getId());
        String rawLeaseToken = tokenService.generateRawToken("lease_");
        Instant leaseExpiresAt = now.plus(tokenProperties.getAttemptLeaseTtl());

        ContributionAttemptDO attempt = new ContributionAttemptDO();
        attempt.setTicketId(ticket.getId());
        attempt.setAttemptNo((maxAttemptNo == null ? 0 : maxAttemptNo) + 1);
        // 创建即 ACTIVE —— 状态机文档写的 READY 态没有任何产生路径（DRIFT D-16 第 4 条）。
        attempt.setStatus(AttemptStatus.ACTIVE.getCode());
        attempt.setLeaseTokenDigest(tokenService.digest(rawLeaseToken));
        attempt.setLeaseIssuedAt(now);
        attempt.setLeaseExpiresAt(leaseExpiresAt);
        attempt.setStartedAt(now);
        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);
        attemptMapper.insert(attempt);

        // ④ 回填「当前进行中的 attempt」。按主键更新，无竞争。
        ticketMapper.linkActiveAttempt(ticket.getId(), attempt.getId(), now);

        // ⑤ 会话推进：有人开始写了 → RUNNING（ACPP 状态机）。
        //    为什么不在 start 时就 RUNNING：start 只是排好了队，没有人在写；
        //    状态要如实反映「有没有一棒正在进行」，否则时间线页会骗人。
        //
        // 🔴 L18 修 bug：这里原本只有 `OPEN → RUNNING` 一个分支。
        //    而 L16 的 SubmitContributionService 写完一棒后一律落 AWAITING_CONTINUATION，
        //    注释白纸黑字写着「等下一棒 claim lease 时再回到 RUNNING」——
        //    ★ 但那个「再回到」从来没有被实现过 ★。
        //    后果：从第 2 棒开始，机娘正在写的时候 session 永远停在 AWAITING_CONTINUATION
        //    （"等人来接"），再也回不到 RUNNING。
        //
        //    ⇒ 为什么这个 bug 能潜伏两课、两轮测试都没红：
        //      核实过 —— session.status 在整个模块里【被写 4 处、被读来做判定 0 处】，
        //      没有任何 SQL 的 WHERE 用到它。★ 一个从来没被读过的状态，写错了也没人会发现。★
        //      L17 说「状态不准是会骗人的」，这里是更狠的后半截：
        //      ★ 一个没人读的状态连骗人的机会都没有，它只是静静地错着 —— 直到有人把它显示出来。★
        //      而 L18 的时间线页正是它的第一个真正消费者，stop 的闸门是它第一次承担判定职责。
        //      在它变成"守卫"之前，必须先把它错了两课的这个 bug 修掉。
        //
        // 🔴 施工期第二处修正：这里原本用 sessionMapper.updateById(session)，被测试打出 Deadlock。
        //    updateById 会 SET 全部列，其中三个是外键列（owner_user_id / planned_channel_id /
        //    tail_handoff_token_id），即使值没变 InnoDB 也要做外键检查、给三张父表的行加 S 锁；
        //    而 AuditListener 正异步插 audit_record（它的 4 个外键也在抢这些行的 S 锁），
        //    两边加锁顺序相反 → 成环。改成只 SET status 的精准 UPDATE 后消失。
        //    ★ updateById 的代价不是"多写几列"，是"多锁几张表"。
        sessionMapper.promoteToRunning(session.getId(), now);

        // ⑥ 发 LeaseClaimed 事件（L18 补 —— 时间线的第三笔欠账）。
        //    V014 的 ck_audit_action_type 从 L17 起就列着 LEASE_CLAIMED，却从来没有代码产生它。
        //    后果：时间线页看不到「这一棒什么时候开始写的」，只能看到提交与超时。
        //    ★ 教训：CHECK 值集里有个值 ≠ 有代码会产生它。值集是承诺，不是实现。
        events.publishEvent(new LeaseClaimed(
                session.getId(), session.getOwnerUserId(), principal.agentAccountId(),
                ticket.getId(), ticket.getSequenceNo(),
                attempt.getId(), attempt.getAttemptNo(), leaseExpiresAt, now));

        return new ClaimLeaseResponse(ticketCode, rawLeaseToken, leaseExpiresAt,
                writingContext(session, ticket));
    }

    /**
     * 给机娘的写作上下文：让它知道自己在给哪篇文章写第几棒，不必再发一轮查询。
     * 只放<b>它写作时真正需要</b>的字段——不放内部自增 id，不放别人的令牌。
     */
    private Map<String, Object> writingContext(CollaborationSessionDO session, ContributionTicketDO ticket) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("postTicket", session.getPostTicket());
        context.put("plannedTitle", session.getPlannedTitle());
        context.put("plannedSummary", session.getPlannedSummary());
        context.put("sequenceNo", ticket.getSequenceNo());
        context.put("isFirstTurn", ticket.getPredecessorTicketId() == null);
        // 已经写完到第几棒：机娘据此判断前面有多少内容可读（草稿 URL 由主人给，机娘无读权）。
        context.put("lastCompletedSequence", session.getLastCompletedSequence());
        return context;
    }

    /**
     * 把「闸门返回 0 行」翻译成具体错误码，让客户端能按 error-actions.md 自愈。
     *
     * <p>★★ 施工期被并发测试打红后才改对的地方，值得记住 ★★
     * <p>第一版我按「回查到什么状态就报什么错」写，兜底落在 {@code ACPP_TICKET_NOT_WRITABLE}。
     * 并发测试立刻红了：8 个败者全部拿到 NOT_WRITABLE，而不是期望的 ALREADY_CLAIMED。
     *
     * <p><b>根因</b>：闸门失败后的这次回查，读到的是<b>事务快照</b>。
     * MySQL 默认 REPEATABLE READ，赢家的 UPDATE 尚未提交时，败者看到的那一行<b>仍然是
     * {@code READY_TO_WRITE}</b>——「看起来明明能领，却领不到」。
     * 于是所有 if 全部落空，掉进兜底分支。
     *
     * <p><b>正确的推理</b>：闸门是唯一的裁决者，它说 0 行就是 0 行。
     * 回查若显示 READY_TO_WRITE，唯一可能的解释就是<b>并发有人先领走了、只是我还看不见</b>——
     * 这与看到 LEASED 是同一件事，应当给出同一个自愈指引：「租约已被领走，请查询席位状态」。
     * 所以 {@code ACPP_TICKET_NOT_WRITABLE} 只留给<b>明确的终态</b>（DONE / FAILED_TIMEOUT）。
     *
     * <p>⚠️ 顺带更正 L15 {@code ClaimHandoffService#rejectionFor} 尾部那句注释——
     * 它把这种情形写成「极罕见（如并发窗口内又被改回）」。<b>并不罕见，在并发路径下是必然</b>；
     * 那里恰好因为兜底值就是 CONSUMED（等于期望值）而没被测试发现。<b>结论对，理由写错了。</b>
     *
     * <p>回查到的状态<b>本来就可能与失败的真实原因不同</b>。这不影响正确性——
     * 我们已经由 affectedRows 确定"你没领到"，回查只为给出更有帮助的提示。
     * 绝不能反过来：拿回查结果当判定依据。
     */
    private ApiException rejectionFor(String ticketCode, AgentIdentity principal) {
        ContributionTicketDO current = ticketMapper.selectByCode(ticketCode);
        if (current == null) {
            return new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }
        // 归属不符优先报 403：这是最有行动价值的一条提示——「换回原机娘」。
        // 同一主人名下的机娘彼此可见，藏起来没有安全收益（泄漏边界按租户划，不按机娘划）。
        if (!Objects.equals(current.getRequiredAgentId(), principal.agentAccountId())) {
            return new ApiException(ApiStatus.ACPP_WRONG_AGENT);
        }
        String status = current.getStatus();
        if (TicketStatus.WAITING_PREDECESSOR.getCode().equals(status)) {
            return new ApiException(ApiStatus.ACPP_TICKET_WAITING);          // 409 → 轮询
        }
        if (TicketStatus.BLOCKED_BY_PREDECESSOR.getCode().equals(status)) {
            return new ApiException(ApiStatus.ACPP_TICKET_BLOCKED);          // 409 → 报告主人
        }
        if (TicketStatus.DONE.getCode().equals(status)
                || TicketStatus.FAILED_TIMEOUT.getCode().equals(status)) {
            return new ApiException(ApiStatus.ACPP_TICKET_NOT_WRITABLE);     // 409 → 明确的终态
        }
        // LEASED（看得见的抢占）与 READY_TO_WRITE（并发快照尚未刷新的抢占）是同一件事。
        return new ApiException(ApiStatus.ACPP_LEASE_ALREADY_CLAIMED);       // 409 → 查席位状态
    }
}
