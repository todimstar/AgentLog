package com.agentlog.forum.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/**
 * comment 表的 DO —— B站式两层评论。对应 V006 的 comment 表，归 forum（社区互动）。
 *
 * 模型："逻辑无限回复，物理永远两层"。三根支柱：
 *  - rootCommentId：楼。一级指向自己；二级指向其所属一级。同楼所有评论 root 相同。
 *  - parentCommentId：直接父。一级为 null；二级指向被回复的那条（可以是另一条二级）。
 *  - replyToCommentId：@谁。纯展示"回复 @某人"，不加深层级。
 * depth 永远 1 或 2（DB 有 CHECK 兜底）。三级状态根本无法表达——回复任何评论都被扁平挂到楼下。
 * status：VISIBLE / DELETED。软删——只改 status 留楼层占位，不物理删（否则子回复成孤儿 + 外键拒删）。
 *
 * 无 Lombok，手写 getter/setter（学习项目透明 > 省行数）。
 */
@TableName("comment")
public class CommentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;               // 挂在哪篇帖子下
    private Long authorUserId;         // 评论人（行级授权键：软删时校验 == 当前登录用户）

    private Long rootCommentId;        // 楼：一级=自己 / 二级=所属一级
    private Long parentCommentId;      // 直接父：一级=null / 二级=被回复的那条
    private Long replyToCommentId;     // @谁：展示用
    private Integer depth;             // 1=一级 / 2=二级（TINYINT，DO 用 Integer 装）

    private String content;
    private String status;             // VISIBLE / DELETED
    private Long likeCount;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getAuthorUserId() {
        return authorUserId;
    }

    public void setAuthorUserId(Long authorUserId) {
        this.authorUserId = authorUserId;
    }

    public Long getRootCommentId() {
        return rootCommentId;
    }

    public void setRootCommentId(Long rootCommentId) {
        this.rootCommentId = rootCommentId;
    }

    public Long getParentCommentId() {
        return parentCommentId;
    }

    public void setParentCommentId(Long parentCommentId) {
        this.parentCommentId = parentCommentId;
    }

    public Long getReplyToCommentId() {
        return replyToCommentId;
    }

    public void setReplyToCommentId(Long replyToCommentId) {
        this.replyToCommentId = replyToCommentId;
    }

    public Integer getDepth() {
        return depth;
    }

    public void setDepth(Integer depth) {
        this.depth = depth;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Long likeCount) {
        this.likeCount = likeCount;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
