package com.agentlog.collaboration.infrastructure.persistence.mapper;

import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionAttemptDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * contribution_attempt 的读写。insert 走 BaseMapper，两条关键语句走 XML。
 *
 * @see com.agentlog.collaboration.application.SubmitContributionService
 */
@Mapper
public interface ContributionAttemptMapper extends BaseMapper<ContributionAttemptDO> {

    /**
     * ★ submit 的闸门：把「租约还有效吗」与「把它标成已完成」合成<b>一条</b> UPDATE。
     *
     * affectedRows 就是裁决书：1 = 这次提交归我；0 = 提交不进来（再回查判定具体原因）。
     * 三个判定条件一个都不许挪到 Java —— 详见 XML 里的注释。
     *
     * @return 影响行数（0 或 1）
     */
    int consumeActiveLease(@Param("leaseTokenDigest") byte[] leaseTokenDigest,
                           @Param("now") Instant now);

    /** 闸门失败后的诊断查询：只用来把「0 行」翻译成具体错误码，不参与互斥判定。 */
    ContributionAttemptDO selectByLeaseDigest(@Param("leaseTokenDigest") byte[] leaseTokenDigest);

    /** 按主键读（submit 通过闸门后取上下文用）。 */
    ContributionAttemptDO selectByIdPlain(@Param("id") Long id);

    /**
     * 这张票已有的最大尝试序号（无记录返回 null → 调用方取 1）。
     *
     * ★ 为什么这里可以安全地「查了再写」：调用它的时候<b>票已经被闸门锁定成 LEASED</b>，
     * 没有第二个线程能走到这一步 —— 也就不存在 TOCTOU。
     * 判据不是「用没用 SELECT」，而是「SELECT 到 INSERT 之间，别人还能不能插进来」。
     */
    Integer selectMaxAttemptNo(@Param("ticketId") Long ticketId);

    /**
     * 这张票<b>最近一次</b>尝试（按 attempt_no 倒序取第一行；从未尝试过返回 null）。
     *
     * <h3>★ L18 补的第三笔旧账</h3>
     * {@code TicketStatusView.errorReportId} 从 L16 起就写着注释「L17 才会有值」，
     * 但 L17 结束了它<b>仍然硬编码为 null</b>。后果：机娘 {@code collab status} 看到
     * {@code FAILED_TIMEOUT} 时拿不到事故报告的指针，也就读不到 {@code suggested_actions_json}
     * （"该怎么办"）——{@code 08-skill/references/error-actions.md} 那套自愈机制在 CLI 侧是断的。
     *
     * <p>★ 三笔旧账（session 回不到 RUNNING / 三个事件没发 / 这一条）是<b>同一个模式</b>：
     * <b>注释是承诺，但没有任何机制保证它被兑现。</b>
     * 测试只测「代码做了什么」，测不出「代码答应了却没做什么」。
     */
    ContributionAttemptDO selectLatestByTicket(@Param("ticketId") Long ticketId);
}
