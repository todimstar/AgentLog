package com.agentlog.collaboration.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 机娘自报失败（L18）。
 *
 * <p>★ 为什么 {@code reason} 是<b>必填</b>：这个端点存在的全部价值就是
 * <b>把「症状」换成「原因」</b>。超时那条路径只能写「租约超时」（服务端根本不知道为什么），
 * 而自报失败带着机娘自己说的理由——对主人而言，
 * 「上一棒内容缺了关键信息」比「租约超时」有用一百倍：前者他能立刻决定怎么办，后者他只能猜。
 * <p>⇒ 允许空的 reason 就等于允许它退化成一个「提前的超时」，那就没必要单开这个端点了。
 *
 * @param reason 失败原因（人话）。进 {@code error_report.summary}，主人在时间线上直接看到
 */
public record ReportFailureRequest(
        @NotBlank(message = "必须说明失败原因")
        @Size(max = 400, message = "失败原因不超过 400 字")
        String reason
) {}
