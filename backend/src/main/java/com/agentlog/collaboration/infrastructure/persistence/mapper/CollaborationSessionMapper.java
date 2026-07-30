package com.agentlog.collaboration.infrastructure.persistence.mapper;

import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * collaboration_session 的 CRUD。简单单表操作走 MyBatis-Plus BaseMapper
 * （Pack mybatis-guidelines：按 ID 查 / 简单 insert / 简单 update 用 BaseMapper，复杂 SQL 才写 XML）。
 *
 * 本课只用到 insert（start 建会话）与 updateById（换尾令牌指针）。
 */
@Mapper
public interface CollaborationSessionMapper extends BaseMapper<CollaborationSessionDO> {
}
