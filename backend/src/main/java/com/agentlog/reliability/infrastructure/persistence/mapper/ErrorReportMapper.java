package com.agentlog.reliability.infrastructure.persistence.mapper;

import com.agentlog.reliability.infrastructure.persistence.dataobject.ErrorReportDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * error_report 的读写。只 INSERT，永不 UPDATE（流水表铁律）。
 */
@Mapper
public interface ErrorReportMapper extends BaseMapper<ErrorReportDO> {

    /**
     * 回填 contribution_attempt.error_report_id。
     *
     * <p>调用时机：error_report 插入成功后，attempt 已在 FAILED_TIMEOUT 状态（闸门已过），
     * 无竞争，按主键更新即可。
     */
    int linkAttemptToErrorReport(@Param("attemptId") Long attemptId,
                                  @Param("errorReportId") Long errorReportId,
                                  @Param("now") java.time.Instant now);
}
