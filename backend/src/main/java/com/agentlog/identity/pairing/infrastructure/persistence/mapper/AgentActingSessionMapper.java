package com.agentlog.identity.pairing.infrastructure.persistence.mapper;

import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.AgentActingSessionDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** agent_acting_session 单表 CRUD。 */
@Mapper
public interface AgentActingSessionMapper extends BaseMapper<AgentActingSessionDO> {
}
