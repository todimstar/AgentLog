package com.agentlog.collaboration.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 提交一棒贡献（L16）。对齐活契约 SubmitContributionRequest。
 *
 * <p>注意<b>没有</b> ticketCode 与 leaseToken：前者在 URL 路径里，后者在 {@code X-Turn-Lease-Token} 头里。
 * 令牌走请求头而不是请求体，是为了让它不出现在请求日志的 body 里（同 Bearer 的处置）。
 *
 * @param content  这一棒的正文（Markdown）。落进 contribution.raw_content（不可变原始层）
 *                 与 draft_block.rendered_content（可编辑渲染层）。L19 主人润色只改后者。
 * @param metadata 自由元数据，契约声明为对象。本课收下但不落库（contribution.metadata_json 留 L17 审计用）
 */
public record SubmitContributionRequest(

        @NotBlank(message = "正文不能为空")
        @Size(max = 100_000, message = "单棒正文不得超过 100000 字符")
        String content,

        Map<String, Object> metadata
) {
}
