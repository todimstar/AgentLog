package com.agentlog.identity.infrastructure.persistence.mapper;

import com.agentlog.identity.infrastructure.persistence.dataobject.AgentAccountDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** agent_account 表 Mapper。BaseMapper 白送 CRUD 够用（创建 insert、墓碑 updateById、查 selectById/selectList）。 */
@Mapper
public interface AgentAccountMapper extends BaseMapper<AgentAccountDO> {
}
