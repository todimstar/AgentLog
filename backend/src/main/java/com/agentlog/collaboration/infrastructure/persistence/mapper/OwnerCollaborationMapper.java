package com.agentlog.collaboration.infrastructure.persistence.mapper;

import com.agentlog.collaboration.infrastructure.persistence.dataobject.TimelineAttemptRow;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.TimelineAuditRow;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.TimelineErrorRow;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.TimelineTicketRow;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 主人决策链的 SQL（L18）：retry / 结束协作 / 重新签发尾令牌 / 时间线查询。
 *
 * <h3>★ 为什么故意不继承 {@code BaseMapper}</h3>
 * 同 L17 的 {@link ExpiredAttemptScanMapper}：继承了就会暴露 {@code updateById} 这种
 * <b>绕过闸门</b>的写法。本课每一个写操作都必须经过带条件的 UPDATE，
 * <b>不给「先查再改」留任何方便的入口</b>。
 *
 * <h3>★ 本课的闸门为什么不止一道（而且落在不同的对象上）</h3>
 * <pre>
 *   retry   → 闸门在【票】     判定的是「这张票是不是超时了」
 *   stop    → 闸门在【会话】   判定的是「这条协作是不是还在跑」
 *   重新签发 → 闸门在【旧令牌】 判定的是「那根尾令牌还在不在」
 * </pre>
 * ★ <b>判据：闸门要选那个「能唯一代表这次操作发生过」的对象。</b>
 * <p>反例：重签若拿 session 当闸门，session 状态在重签前后<b>根本不变</b>，
 * 它记不住「重签发生过」，两个并发请求会双双通过、签出两根令牌。
 * <p>而 stop 若拿某一张票当闸门——拿哪张？它的操作对象是整条协作，没有"某一张票"；
 * 随便挑一张的话，两个标签页各挑一张，双双通过。
 */
@Mapper
public interface OwnerCollaborationMapper {

    // ═══════════════ retry：解冻三处 + 一道闸门 ═══════════════

    /**
     * ★ retry 的闸门 ★：把超时的票改回可写。{@code affectedRows} 就是裁决书。
     *
     * <p>★ <b>这道闸门同时就是幂等</b>——你在时间线页上连点两下，第二下一定落在这里返回 0。
     * 所以 retry <b>不需要</b> {@code @Idempotent}（L16 那套）：
     * <ul>
     *   <li>幂等要解决的是「重发会产生<b>第二次真实副作用</b>」（L16 submit 会写出两段正文）；
     *       retry 重发只是闸门返回 0，<b>什么都不会发生</b>；</li>
     *   <li>而且幂等键<b>由客户端生成</b>——浏览器点两次是两个不同的 key，幂等根本认不出它们是同一件事。</li>
     * </ul>
     * ★ 一句话：<b>幂等防的是「同一个请求被重发」，闸门防的是「这件事被重复执行」——不管来的是不是同一个请求。</b>
     *
     * <p>⚠️ SQL 里<b>连带校验了 session 必须是 {@code PAUSED_ON_ERROR}</b>——
     * 因为 retry 与「结束协作」的闸门落在<b>不同对象</b>上（票 vs 会话），互相拦不住。
     * 详见 XML 里的推理。
     *
     * @return 1 = 本次真的由我 retry；0 = 已被 retry 过 / 票不是超时态 / 协作已经不在暂停态
     */
    int retryTicket(@Param("ticketCode") String ticketCode, @Param("now") Instant now);

    /** retry 成功后把会话推回「等人来接」（不是 RUNNING——此刻还没有任何人在写）。 */
    int resumeSessionAfterRetry(@Param("sessionId") Long sessionId, @Param("now") Instant now);

    /**
     * 按对外标识取会话 id，<b>同时做行级授权</b>（不是你的主人的 → 查不到 → 一律 404）。
     *
     * <p>★ 把 {@code owner_user_id} 写进 WHERE 而不是查出来再比对：
     * 让「越权」在 SQL 层就变成「不存在」，调用方<b>没有忘记判断的机会</b>。
     * 跨 owner 返 404 而非 403 —— 403 等于承认「它存在、只是不给你」，会泄漏资源存在性。
     */
    Long selectSessionIdByPostTicket(@Param("postTicket") String postTicket,
                                     @Param("ownerUserId") Long ownerUserId);

    /**
     * 解冻<b>整条尾巴</b>：沿因果链把所有被阻塞的后序票放回排队态。
     *
     * <p>★ 是 L17 {@code blockSuccessors} 的镜像，但<b>目标状态不同</b>：
     * 改成 {@code WAITING_PREDECESSOR}（继续排队），<b>不是</b> {@code READY_TO_WRITE}（可以写）。
     * 因为前一棒只是"可以重写了"，<b>还没写完</b>——第 3 棒此刻仍然没有资格落笔。
     * 等前一棒真的 submit 成功，L16 的 {@code wakeSuccessor} 会把它唤醒（那一步只走一步）。
     *
     * <p>⚠️ 顺带修正了蓝图状态机的一条边：{@code ACPP状态机.md} 原文写
     * {@code BLOCKED_BY_PREDECESSOR --> READY_TO_WRITE: 前序恢复}，<b>把两步合成了一步</b>。
     *
     * @return 解冻了几张（0 也是正常的：retry 的可能是链尾那一棒，后面本来就没人）
     */
    int unblockSuccessors(@Param("predecessorTicketId") Long predecessorTicketId,
                          @Param("now") Instant now);

    /**
     * 解冻尾令牌：{@code FROZEN → AVAILABLE}，让新机娘又能排到队尾。
     *
     * <p>★ 与解冻后序票是<b>两个正交维度</b>（L17 冻结时就是这么分的）：
     * 后序票管「<b>已经进来的人</b>能不能写」，尾令牌管「<b>还能不能有新人</b>进来」。
     *
     * @return 1 = 解冻了；0 = 它本来就不是 FROZEN（比如已被消费或已吊销）
     */
    int unfreezeTailToken(@Param("sessionId") Long sessionId);

    // ═══════════════ 结束协作：一道闸门 + 四步收尾 ═══════════════

    /**
     * ★ 结束协作的闸门 ★：会话推进到 {@code READY_FOR_OWNER_REVIEW}（协作的<b>唯一出口</b>）。
     *
     * <p>★ 为什么只有一个出口（主人 2026-08-15 的产品判断）：蓝图画了两条出边
     * （正常收工 → {@code READY_FOR_OWNER_REVIEW}、出错放弃 → {@code TERMINATED}），
     * 但两者对<b>草稿</b>而言结果完全相同——协作不再占着它。内容是删是留是草稿模块的事。
     * <b>别让一个机制回答两个问题。</b>
     *
     * <p>WHERE 里的四个状态 = 所有「还没结束」的态。{@code INVALIDATED}（首棒失败）
     * 与已结束的态不在其中——它们已是终态，没有草稿要解锁，主人也不需要按任何按钮。
     */
    int stopSession(@Param("sessionId") Long sessionId, @Param("now") Instant now);

    /** 结束时若有人正在写，取回那条 attempt 的 id（只为写审计；判定仍由下面的条件 UPDATE 做）。 */
    Long selectActiveAttemptId(@Param("sessionId") Long sessionId);

    /**
     * 吊销进行中的 attempt：{@code ACTIVE → REVOKED}，它再 submit 就会撞闸门吃 409。
     *
     * <p>★ 这里是本课<b>唯一的真竞态</b>：你按下「结束协作」的同一毫秒，机娘正好 submit 成功了。
     * 两条 UPDATE 争同一行，MySQL 行锁让它们串行，<b>谁先谁赢，两边都不会出现半截状态</b>：
     * <pre>
     *   机娘赢 → attempt=SUCCEEDED、票=DONE、正文进草稿。
     *            本方法影响 0 行；下面 cancelUnfinishedTickets 的 WHERE 也排除了 DONE。
     *            ⇒ ★ 那一棒的内容【保住了】——它确实在你按下按钮之前写完了。
     *   我们赢 → attempt=REVOKED，机娘的 submit 闸门（WHERE status='ACTIVE'）返回 0 → 409。
     * </pre>
     */
    int revokeActiveAttempts(@Param("sessionId") Long sessionId, @Param("now") Instant now);

    /**
     * 取消所有未完成的席位 → {@code CANCELLED}。
     *
     * <p>★ 它存在的理由是<b>让还在轮询的机娘停下来</b>，不是"状态机好看"：
     * 不改的话，第 3 棒的机娘跑着 {@code collab wait} 查到票仍是 {@code WAITING_PREDECESSOR}，
     * 服务端答「5 秒后再来问」——它会等一个永远不来的信号直到 900 秒超时。
     * 那句话是真的，但真相是「协作已经收工了」。
     *
     * <p>⚠️ WHERE 里<b>排除 DONE 与 FAILED_TIMEOUT</b>：它们是<b>历史</b>，历史不能改。
     */
    int cancelUnfinishedTickets(@Param("sessionId") Long sessionId, @Param("now") Instant now);

    /** 吊销尾令牌 → {@code REVOKED}：协作结束了，不能再有人进来。 */
    int revokeTailToken(@Param("sessionId") Long sessionId);

    /**
     * 重算「已完成到第几棒」。
     *
     * <p>★ 为什么需要这一步（一个被 {@code @Version} 逼出来的细节）：
     * {@code CollaborationSessionDO} 带 {@code @Version}，submit 走 {@code updateById} 是<b>乐观锁</b>。
     * 若 stop 先提交（version+1），submit 那次 {@code updateById} 会静默影响 0 行——
     * 内容与票都落对了，但 {@code last_completed_sequence} <b>没跟上</b>。
     * <p>与其去改 submit 的写法（那会动 L16 的主干），不如让 stop <b>自己按事实重算一次</b>：
     * 从票里取 DONE 的最大 {@code sequence_no}。这一步<b>幂等且必然正确</b>，
     * 因为它不依赖"谁先谁后"，只依赖"哪些票真的写完了"。
     * <p>★ 判据：<b>能从事实推导出来的派生值，就不要依赖执行顺序去维护它。</b>
     */
    int recalcLastCompletedSequence(@Param("sessionId") Long sessionId, @Param("now") Instant now);

    // ═══════════════ 重新签发尾令牌 ═══════════════

    /**
     * ★ 重签的闸门 ★：吊销旧的那根尾令牌。
     *
     * <p><b>为什么是「重新签发」而不是「重新查看」</b>——这是本课一个反直觉的点：
     * <pre>
     *   handoff_token 表里存的是  token_digest BINARY(32) = HMAC-SHA256(pepper, 明文)
     *   摘要是【单向】的：有明文能算出摘要，有摘要【永远】算不回明文。
     *   明文的一生：签发那一刻放进 HTTP 响应 → 主人复制走 → 服务端内存里那份被 GC 回收
     *                                                    ↓
     *                                        ★ 从此世上只有主人手里那一份 ★
     * </pre>
     * 所以 UX 规格里那句「可查看下一棒尾令牌」（{@code 06-web/owner-review-ux.md}）
     * <b>物理上做不到</b>——服务端自己都不知道它长什么样。
     * <p>★ 判据：<b>当 UX 需求撞上安全模型时，往往不是砍需求，而是换一个能满足它的机制。</b>
     * 主人真正要的是「我能拿到一根可用的令牌」，不是「我要看那一根特定的令牌」。
     *
     * <p>⚠️ 旧令牌<b>必须真的吊销</b>，不能只是"不管它"：如果那串明文流到了别人手里
     * （比如被粘进了公开的聊天记录），不吊销就等于留了一个可用的后门。
     *
     * @return 1 = 我吊销成功，可以签发新的；0 = 它已被消费/已吊销，说明局面变了
     */
    int revokeHandoffById(@Param("tokenId") Long tokenId);

    /** 换上新的尾令牌指针（闸门已过，无竞争）。 */
    int updateTailHandoff(@Param("sessionId") Long sessionId,
                          @Param("tokenId") Long tokenId,
                          @Param("now") Instant now);

    // ═══════════════ 机娘自报失败 ═══════════════

    /**
     * ★ 自报失败的闸门 ★：{@code ACTIVE → FAILED_CLIENT}，凭租约摘要认人。
     *
     * <p>★ 与 L17 Worker 那道闸门（{@code markAttemptTimeout}）的区别，正好是<b>互补</b>的：
     * <pre>
     *   Worker ：WHERE status='ACTIVE' AND lease_expires_at &lt;  now   （已过期才轮到我收尸）
     *   自报   ：WHERE lease_token_digest=? AND status='ACTIVE'        （凭证对得上就认）
     * </pre>
     * 不校验是否过期——机娘说"我写不下去了"这件事，<b>租约过没过期都成立</b>。
     * 若恰好和 Worker 撞上，行锁让二者串行，谁先谁赢，另一个拿 0 行干净退出。
     */
    int markAttemptFailedByClient(@Param("leaseTokenDigest") byte[] leaseTokenDigest,
                                  @Param("now") Instant now);

    /** 凭租约摘要取 attempt（自报失败时定位上下文用）。 */
    TimelineAttemptRow selectAttemptByLeaseDigest(@Param("leaseTokenDigest") byte[] leaseTokenDigest);

    // ═══════════════ 时间线查询（跨模块只读投影，D-05 的正路）═══════════════

    /** 席位全景 + 机娘展示名（join identity 的 agent_account）。按棒次升序。 */
    java.util.List<TimelineTicketRow> selectTicketTimeline(@Param("sessionId") Long sessionId);

    /** 每一次写作尝试。retry 过的票会有多行（attempt_no 1、2、…），旧的原样保留。 */
    java.util.List<TimelineAttemptRow> selectAttemptTimeline(@Param("sessionId") Long sessionId);

    /** 动作流水（join audit 模块的 audit_record + identity 的 agent_account）。按时间升序。 */
    java.util.List<TimelineAuditRow> selectAuditTimeline(@Param("sessionId") Long sessionId);

    /** 事故报告（join reliability 模块的 error_report）。带 suggested_actions_json 驱动前端按钮。 */
    java.util.List<TimelineErrorRow> selectErrorTimeline(@Param("sessionId") Long sessionId);

    /**
     * 机娘展示名（跨模块只读投影 → identity 的 {@code agent_account}）。
     *
     * <p>retry 成功后页面要告诉主人「现在去叫谁」。由服务端给，前端不必再查一次机娘列表。
     */
    String selectAgentNickname(@Param("agentId") Long agentId);
}
