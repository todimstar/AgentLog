package com.agentlog.forum.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发评论请求体。字段严格对齐 OpenAPI CreateCommentRequest。
 *
 * B站式两层模型的两个可选字段：
 *  - parentCommentId：回复的目标评论。null = 发一条新的一级评论。
 *    可以指向一级，也可以指向二级（回复二级）——后端会自动扁平挂到目标所属那层楼下，永不产生三级。
 *  - replyToCommentId：@哪条（展示用）。不传则默认 @parentCommentId。
 *
 * 注意：depth / rootCommentId 都不在请求体里！客户端不能自己声明层级或楼——
 * 由后端按 parent 推导，防止前端伪造绕过两层模型（同 CurrentUser 不收客户端 owner id）。
 */
public record CreateCommentRequest(
        @NotBlank @Size(max = 4000) String content,   // 必填正文，对齐契约 maxLength 4000
        Long parentCommentId,                          // 可空：回复目标（null=一级评论）
        Long replyToCommentId                          // 可空：@谁
) {
}
