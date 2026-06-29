package com.agentlog.forum.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * reaction 表的 DO —— 点赞记录。对应 V007 的 reaction 表，归 forum（社区互动）。
 *
 * 多态：一张表存 POST 和 COMMENT 的点赞，靠 target_type 区分指向哪种对象、target_id 指哪条。
 * 不画外键到 post/comment —— 多态对象异构，FK 画不了，有效 target_id 由应用层保证。
 *
 * 唯一键 uk_reaction_user_target(user_id, target_type, target_id) 是本课 toggle 的物理地基：
 * INSERT IGNORE 时若唯一键冲突（已赞过）→ affected rows=0；未冲突 → 1。DB 替你判断加/删。
 *
 * 手写 getter/setter（无 Lombok）：DO 需可变 class，MyBatis-Plus 靠无参构造 + setter 回填。
 */
@TableName("reaction")
public class ReactionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;            // 点赞人（行级授权键）
    private String targetType;      // POST / COMMENT
    private Long targetId;          // 目标 id（post.id 或 comment.id）
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

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}