package com.agentlog.forum.api.dto.response;

import com.agentlog.forum.infrastructure.persistence.dataobject.CommentRow;

import java.time.Instant;

/**
 * 单条评论视图。对齐 OpenAPI CommentView，并超集补齐两层树前端必需的结构字段。
 *
 * 契约最小集：id / author / content / likeCount / createdAt。
 * 本课在此之上补 5 个前端渲染必需字段（契约是下限，不是上限）：
 *  - rootCommentId：楼。前端据此把同楼评论聚成一组（一级 + 它下面所有二级回复）。
 *  - parentCommentId：直接父。
 *  - replyToCommentId：渲染"回复 @某某"。
 *  - depth：1 一级 / 2 二级，前端缩进（二级全部平铺在楼内，不再加深）。
 *  - status：VISIBLE / DELETED，DELETED 时前端渲染"该评论已删除"占位（软删保楼层）。
 *
 * 软删占位脱敏：status=DELETED 的行，content 不外泄，author 也匿名化。
 */
public record CommentView(
        Long id,
        AuthorView author,
        Long rootCommentId,
        Long parentCommentId,
        Long replyToCommentId,
        Integer depth,
        String content,
        String status,
        Long likeCount,
        Instant createdAt
) {
    public static CommentView from(CommentRow row) {
        boolean deleted = "DELETED".equals(row.getStatus());
        // 软删行：内容与作者脱敏，只保留楼层结构（id/root/parent/depth/时间）让前端撑出占位。
        AuthorView author = deleted
                ? new AuthorView(null, "已注销")
                : new AuthorView(row.getAuthorUserId(), row.getAuthorName());
        String content = deleted ? "该评论已删除" : row.getContent();
        return new CommentView(
                row.getId(), author,
                row.getRootCommentId(), row.getParentCommentId(), row.getReplyToCommentId(), row.getDepth(),
                content, row.getStatus(), row.getLikeCount(), row.getCreatedAt());
    }
}
