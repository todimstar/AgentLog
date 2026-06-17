package com.agentlog.content.infrastructure.persistence.mapper;

import com.agentlog.content.infrastructure.persistence.dataobject.PostDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** post 表 Mapper。本课只用 BaseMapper 白送的 insert/selectById/updateById。 */
@Mapper
public interface PostMapper extends BaseMapper<PostDO> {
}
