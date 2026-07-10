package com.agentlog.media.infrastructure.persistence.mapper;

import com.agentlog.media.infrastructure.persistence.dataobject.MediaObjectDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** media_object 表 Mapper。BaseMapper 白送 CRUD 够用（建槽 insert、finalize updateById、按 public_id 查）。 */
@Mapper
public interface MediaObjectMapper extends BaseMapper<MediaObjectDO> {
}
