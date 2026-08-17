package com.agentlog.collaboration.infrastructure.persistence.mapper;

import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * collaboration_session 的 CRUD。insert 走 BaseMapper，<b>状态推进一律走下面的精准 UPDATE</b>。
 *
 * <h3>🔴 L18 起不再用 {@code updateById} 推进会话状态——起因是一次真实的死锁</h3>
 * 施工期被测试打红（{@code DeadlockLoserDataAccessException}），根因：
 * <pre>
 *   MyBatis-Plus 的 updateById 会 SET 【全部列】：
 *     SET post_ticket=?, owner_user_id=?, planned_channel_id=?, tail_handoff_token_id=?, status=?, ...
 *          ↑ owner_user_id / planned_channel_id / tail_handoff_token_id 【三个都是外键列】
 *   即使值一个字都没变，InnoDB 也会为它们做外键检查 ——
 *   对 user_account / forum_channel / handoff_token 的父行各加一把【S 锁】。
 *
 *   与此同时 AuditListener 正在异步插入 audit_record，
 *   它的 4 个外键同样要对 collaboration_session / contribution_ticket / agent_account 的父行加 S 锁。
 *
 *   两边【加锁顺序相反】 → 成环 → Deadlock。
 * </pre>
 *
 * <p>★ 判据：<b>{@code updateById} 的代价不只是"多写几列"，而是"多锁几张表"。</b>
 * 一条 UPDATE 碰到的外键列越多，牵连的锁就越多，与并发写入者撞车的面就越大。
 * <p>这与 L17 让 {@code ExpiredAttemptScanMapper} <b>故意不继承 BaseMapper</b> 是同一条思路
 * （那次是为了不暴露绕过闸门的写法），只是这次的理由更硬——<b>它真的把系统锁死了</b>。
 *
 * <p>⚠️ <b>为什么 L15–L17 一直没炸</b>：{@code updateById} 从 L15 就在用，但并发度不够——
 * 审计事件此前只有「超时」和「提交」两种，量少且不密集。
 * L18 给 claim lease 补上 {@code LEASE_CLAIMED} 事件后，每领一次租约就多一次审计写入，
 * 撞车概率陡增，这个从 L15 就埋着的隐患才浮出水面。
 * <b>并发缺陷的暴露需要压力，而压力常常由一个看似无关的新功能提供。</b>
 */
@Mapper
public interface CollaborationSessionMapper extends BaseMapper<CollaborationSessionDO> {

    /**
     * 有人开始写了：{@code OPEN}（首棒）或 {@code AWAITING_CONTINUATION}（后续棒）→ {@code RUNNING}。
     *
     * <p>🔴 <b>L18 修的 L16 遗留 bug 就在这个"或"上</b>：
     * L16 的 {@code SubmitContributionService} 注释写着「等下一棒 claim lease 时再回到 RUNNING」，
     * 但 {@code ClaimLeaseService} 里<b>只判了 OPEN</b>——那个「再回到」从来没被实现过。
     * 于是从第 2 棒起，机娘正在写的时候会话永远停在「等人来接」。
     */
    int promoteToRunning(@Param("sessionId") Long sessionId, @Param("now") Instant now);

    /**
     * 一棒写完：{@code → AWAITING_CONTINUATION}，同时推进「已完成到第几棒」。
     *
     * <p>状态<b>一律</b>落 AWAITING_CONTINUATION、无分支：因果链是串行的（后序票必须等前序 DONE
     * 才能领租约），所以 submit 完成那一刻<b>必然没有任何一棒在写</b>。
     *
     * <p>⚠️ WHERE 限定「还在跑」的三个状态：若主人恰好在此刻结束了协作，这条 UPDATE 影响 0 行——
     * <b>内容与席位照常落库，但会话状态不被拉回</b>。这正是我们要的：主人的"结束"决定优先。
     */
    int markAwaitingContinuation(@Param("sessionId") Long sessionId,
                                 @Param("sequenceNo") Integer sequenceNo,
                                 @Param("now") Instant now);

    /**
     * 一棒失败：首棒 → {@code INVALIDATED}，中间棒 → {@code PAUSED_ON_ERROR}。
     *
     * @param status 由调用方按「是不是首棒」决定——判据是<b>有没有草稿可救</b>，
     *               而不是「怎么失败的」（Worker 超时与机娘自报走的是同一条）
     */
    int markFailed(@Param("sessionId") Long sessionId,
                   @Param("status") String status,
                   @Param("now") Instant now);

    /** 换链尾指针（start / join 签发新尾令牌之后）。只碰这一列，不牵连其它外键。 */
    int updateTailHandoffToken(@Param("sessionId") Long sessionId,
                               @Param("tokenId") Long tokenId,
                               @Param("now") Instant now);

    /** 首棒 submit 后把新建的 post/draft 挂回会话。只在 {@code draft_id} 仍为 NULL 时生效。 */
    int linkPostAndDraft(@Param("sessionId") Long sessionId,
                         @Param("postId") Long postId,
                         @Param("draftId") Long draftId,
                         @Param("now") Instant now);
}
