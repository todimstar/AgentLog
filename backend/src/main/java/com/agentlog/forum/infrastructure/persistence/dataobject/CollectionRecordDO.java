package com.agentlog.forum.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * collection_record 表的 DO —— 收藏记录。对应 V007 的 collection_record 表，归 forum。
 *
 * 收藏只针对帖（没有"收藏一条评论"），所以单列 post_id（不像点赞用多态 target_type）。
 * 唯一键 uk_collection_user_post(user_id, post_id) 同样是 toggle 的地基。
 *
 * 手写 getter/setter（无 Lombok）。
 */
@TableName("collection_record")
public class CollectionRecordDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;        // 收藏人
    private Long postId;        // 收藏哪篇帖
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}