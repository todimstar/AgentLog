package com.agentlog.audit.infrastructure.persistence.mapper;

import com.agentlog.audit.infrastructure.persistence.dataobject.AuditRecordDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * audit_record 的写入。只 INSERT，永不 UPDATE（流水表铁律）。
 * BaseMapper 的 insert() 足够，不需要自定义方法。
 */
@Mapper
public interface AuditRecordMapper extends BaseMapper<AuditRecordDO> {
}
