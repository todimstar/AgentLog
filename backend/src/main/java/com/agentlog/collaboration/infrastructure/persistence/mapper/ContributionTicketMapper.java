package com.agentlog.collaboration.infrastructure.persistence.mapper;

import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * contribution_ticket 的读写。简单单表操作走 BaseMapper，状态推进走 XML 的条件 UPDATE。
 *
 * L15 用到 insert（start 建首棒票 / join 建后续票）与 selectById（读前序票判定状态）；
 * L16 追加席位的完整状态机推进（领租约 / 完成 / 唤醒后继）。
 */
@Mapper
public interface ContributionTicketMapper extends BaseMapper<ContributionTicketDO> {

    /** 按对外标识（CT-xxxx）查。是 L16 三个端点的入口查询。 */
    ContributionTicketDO selectByCode(@Param("ticketCode") String ticketCode);

    /**
     * ★ claim lease 的闸门：把「这一棒能不能领」与「标记成已领」合成<b>一条</b> UPDATE。
     *
     * ★ 为什么闸门必须排在「建 attempt」<b>之前</b>（L15 踩过的坑的同构版）：
     * 若顺序写成「查票 → 建 attempt → 改票状态」，两个线程会<b>双双先建 attempt</b>，
     * 撞上 uk_attempt_ticket_no，败者拿到的是 DuplicateKeyException（500），
     * 而不是干净的 409 +「请查询席位状态」。
     * 更要命的是：唯一键只挡得住"撞车"，挡不住"你本来就没资格上路"
     * （票不是你的 / 状态是 WAITING / 会话已终止 —— 这些情形下压根没人跟你抢，票会建成功并返回 200）。
     *
     * ★ required_agent_id 也写进 WHERE：这一列建票后不会变、理论上没有 TOCTOU，
     * 但保持「判定权 100% 在这条 UPDATE 手里」的范式纯度；失败后回查翻译成 ACPP_WRONG_AGENT(403)。
     *
     * @return 影响行数（0 或 1）
     */
    int claimForLease(@Param("ticketCode") String ticketCode,
                      @Param("agentId") Long agentId,
                      @Param("now") Instant now);

    /**
     * 回填「当前进行中的 attempt」。按主键更新，天然无竞争 ——
     * 能走到这一行的只有已经通过上面那道闸门的赢家。
     *
     * 为什么不合进 claimForLease：合并就必须先建 attempt 才能改票，于是又回到了上面说的错误顺序。
     */
    int linkActiveAttempt(@Param("ticketId") Long ticketId,
                          @Param("attemptId") Long attemptId,
                          @Param("now") Instant now);

    /** submit 成功后把席位标成完成。调用点已通过 attempt 闸门，无竞争。 */
    int markDone(@Param("ticketId") Long ticketId, @Param("now") Instant now);

    /**
     * ★ 唤醒直接后继：把「等着我」的那张票从 WAITING_PREDECESSOR 放行为 READY_TO_WRITE。
     *
     * 这就是 APPROVAL_RECORD 里「后序可提前排队，不可提前写」的兑现点 ——
     * 第二个机娘在 L15 的 join 那一刻就拿到了票，但一直卡在 WAITING_PREDECESSOR，
     * 直到前一棒 submit，这条 UPDATE 才放它过去。CLI {@code collab wait} 轮询等的就是这一刻。
     *
     * status 写进 WHERE 而不是无条件更新：BLOCKED_BY_PREDECESSOR（L17 冻结）与 DONE 都不该被这里唤醒。
     */
    int wakeSuccessor(@Param("predecessorTicketId") Long predecessorTicketId,
                      @Param("now") Instant now);
}
