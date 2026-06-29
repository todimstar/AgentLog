package com.agentlog.forum.api.dto.response;

import java.util.List;

/**
 * 评论分页结果 = 扁平评论列表 + 分页元信息。对齐 OpenAPI CommentPage。
 *
 * 注意 items 是【扁平一维数组】，不是嵌套树——契约如此约定：后端按楼层顺序吐平铺评论，
 * 前端顺序分组成两层树。后端不组树（少一层嵌套 DTO，前端拿 parentCommentId 自己分组更灵活）。
 */
public record CommentPage(
        List<CommentView> items,
        PageMeta meta
) {
}
