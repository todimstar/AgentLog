package com.agentlog.content.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 建草稿请求体。字段对齐 OpenAPI CreateOwnerDraftRequest（本课忽略 tagIds，标签留到 L21）。
 *
 * content 是主人写的正文（接口入参），后端会拆存到 contribution + draft_block，
 * 不会塞进 post 表——不违反"禁止 post.content"。
 */
public record CreateOwnerDraftRequest(
        @NotBlank String title,                 // 必填：标题
        @NotNull Long channelId,                // 必填：发到哪个分区
        @NotBlank String content,               // 必填：正文
        String summary,                         // 可空：摘要
        boolean declaredExternalAiContent       // 是否声明含外部 AI 内容，默认 false
    ) {// record如遇未传来，会将可选也自动填写null和false默认值了
    
}