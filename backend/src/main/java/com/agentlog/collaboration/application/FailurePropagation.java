package com.agentlog.collaboration.application;

import com.agentlog.collaboration.domain.SessionStatus;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ExpiredAttemptScanMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 「一棒死了之后，失败怎么沿因果链传播」——L17 的状态推进，L18 抽出来共用。
 *
 * <h3>★ 为什么值得抽（不是为了整洁，是被第二个调用方逼出来的）</h3>
 * L17 定下的分工判据是：<b>「何时做」归 reliability，「做什么」归 collaboration</b>。
 * 当时只有一个「何时」——Worker 扫到租约超时。
 * <p>L18 出现了<b>第二个「何时」</b>：机娘自己报告写不下去了（不等 15 分钟超时）。
 * 两者<b>后续要做的事完全相同</b>——票落失败态、后序整条尾巴阻塞、尾令牌冻结、会话暂停或作废。
 * <p>⇒ 这恰好验证了 L17 那条判据的价值：<b>把「做什么」独立出来之后，加一个新的触发者
 * 不需要复制任何逻辑</b>。若当初把这些步骤写死在 Worker 里，L18 就只能复制粘贴一份——
 * 而复制出来的两份<b>一定会漂移</b>（一边改了另一边忘了）。
 *
 * <h3>★ 它【不含】闸门</h3>
 * 闸门必须由各自的调用方持有，因为<b>两者判定的是不同的事</b>：
 * <pre>
 *   Worker ：WHERE status='ACTIVE' AND lease_expires_at &lt; now   （已过期才轮到我收尸）
 *   自报失败：WHERE lease_token_digest=? AND status='ACTIVE'       （凭证对得上就认，不问过没过期）
 * </pre>
 * 调用本类之前，调用方必须<b>已经通过自己的闸门独占了那条 attempt</b>——
 * 所以这里面的每一步都没有竞争者。
 */
@Component
public class FailurePropagation {

    private final ExpiredAttemptScanMapper scanMapper;
    private final CollaborationSessionMapper sessionMapper;

    public FailurePropagation(ExpiredAttemptScanMapper scanMapper,
                              CollaborationSessionMapper sessionMapper) {
        this.scanMapper = scanMapper;
        this.sessionMapper = sessionMapper;
    }

    /**
     * 把一棒的失败沿因果链传播出去（四步）。<b>调用前必须已通过闸门独占该 attempt。</b>
     *
     * <pre>
     *   ① ticket  → FAILED_TIMEOUT
     *   ② 后序整条尾巴 → BLOCKED_BY_PREDECESSOR（递归 CTE 走到底）
     *   ③ 尾令牌 → FROZEN（永远 1 根）
     *   ④ session → INVALIDATED（首棒）/ PAUSED_ON_ERROR（中间棒）
     * </pre>
     *
     * ★ ②与③是<b>两个正交维度</b>：②管「已经进来的人能不能写」，③管「还能不能有新人进来」。
     *
     * @return true = 这是首棒（会话已作废，无草稿可救）；false = 中间棒（会话暂停，可 retry）
     */
    public boolean propagate(ContributionTicketDO ticket, CollaborationSessionDO session, Instant now) {
        // ① 这一棒废了。
        scanMapper.markTicketTimeout(ticket.getId(), now);

        // ② 后序【整条尾巴】阻塞。
        //    ★ 必须走到底，不是只走一层 —— L17 原实现只冻直接后继一张，
        //      而测试只建了 3 棒（第 3 棒恰好是直接后继）所以测绿了。
        //      判据：同一条链表，唤醒只走一步（理由只对直接后继成立），
        //            冻结要走到底（"内容缺了一块"对整条尾巴都成立）。
        scanMapper.blockSuccessors(ticket.getId(), now);

        // ③ 尾令牌冻结：不能再有新人进来排队。
        scanMapper.freezeTailToken(session.getId(), now);

        // ④ 会话推进。
        //    首棒失败 → INVALIDATED：post/draft 从来没被创建过（「首棒失败不暴露空草稿」），没东西可救，
        //                             所以 L17 给的建议动作里【不含】RETRY_TICKET，L18 的 retry 闸门也拒绝它。
        //    中间棒失败 → PAUSED_ON_ERROR：草稿还在，等主人 retry。
        boolean firstTurn = (ticket.getPredecessorTicketId() == null);
        // 🔴 L18：改用精准 UPDATE，不再 updateById —— 后者 SET 全部列会连带锁住三张父表，
        //    与 AuditListener 的异步审计写入形成死锁环（详见 CollaborationSessionMapper 的注释）。
        sessionMapper.markFailed(session.getId(),
                firstTurn ? SessionStatus.INVALIDATED.getCode() : SessionStatus.PAUSED_ON_ERROR.getCode(),
                now);

        return firstTurn;
    }
}
