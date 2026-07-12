package com.agentlog.identity.pairing.infrastructure.persistence.mapper;

import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.DevicePairingRequestDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** device_pairing_request 单表 CRUD。 */
@Mapper
public interface DevicePairingRequestMapper extends BaseMapper<DevicePairingRequestDO> {
}
