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
}
