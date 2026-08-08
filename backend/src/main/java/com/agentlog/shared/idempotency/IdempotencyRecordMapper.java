package com.agentlog.shared.idempotency;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** idempotency_record 表 Mapper。查询走 Wrappers，无需自定义 SQL。 */
@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecordDO> {
}
