package com.agentlog.forum.infrastructure.persistence.dataobject;

import java.time.Instant;

/**
 * 评论树平铺查询的扁平行 —— 一次 JOIN 把"评论 + 作者名"拉全，喂给 Service 内存分组。
 *
 * 为什么不直接用 CommentDO？
 *  - CommentDO 是单表写模型（无作者名，作者名在 user_account 另一张表）。
 *  - 读评论树要带作者展示名，若先查评论再逐条查作者名 = N+1。
 *  - 所以读侧用 JOIN 一次查回 CommentRow（评论字段 + authorName），这就是 CQRS 读模型行。
 *
 * 手写 getter/setter（MyBatis resultType 反射回填需可变 + 无参构造）。
 */
public class CommentRow {

    private Long id;
    private Long rootCommentId;         // 楼（前端据此把同楼评论聚到一起）
    private Long parentCommentId;       // 直接父
    private Long replyToCommentId;      // @谁
    private Integer depth;
    private Long authorUserId;
    private String authorName;          // JOIN user_account 查回的展示名
    private String content;
    private String status;              // VISIBLE / DELETED（前端据此渲染"已删除"占位）
    private Long likeCount;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getAuthorUserId() {
        return authorUserId;
    }

    public void setAuthorUserId(Long authorUserId) {
        this.authorUserId = authorUserId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
