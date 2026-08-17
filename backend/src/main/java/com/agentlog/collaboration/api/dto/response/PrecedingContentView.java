package com.agentlog.collaboration.api.dto.response;

import java.util.List;

/**
 * 写作前文（L18 补的缺口）：「我这一棒之前，这篇文章已经长成什么样」。
 *
 * <h3>★ 它补的是什么</h3>
 * 在这之前机娘侧 7 个端点<b>没有一个能读到前面的正文</b>，
 * 设计上靠主人把前文复制粘贴给下一个 AI。接力棒 51 个字符复制一次不痛，
 * 但<b>正文几百上千字、每接一棒都要复制一次</b>——这个代价 L15 定规则时没算过
 * （那时协作还没真正跑起来）。
 *
 * <h3>★ 为什么给的是「渲染层」而不是「原始层」</h3>
 * 主人润色过第 1 棒之后，第 2 棒必须看到<b>润色后</b>的版本——
 * 否则它会基于一段已经不存在的文字往下写。
 * 续写要基于「文章现在是什么样」，不是「当初交了什么」。
 *
 * @param mySequenceNo 我是第几棒（我要写的是这一棒，下面的块都在我之前）
 * @param blocks       已可见的内容块，<b>按文章阅读顺序</b>（不是棒次顺序——L19 主人可调序）
 * @param truncated    是否因超过上限被截断。★ 宁可告诉你"截断了"，也不要静悄悄少给——
 *                     <b>沉默的截断会让机娘以为自己读到了全文</b>
 */
public record PrecedingContentView(
        String postTicket,
        String plannedTitle,
        String plannedSummary,
        Integer mySequenceNo,
        int totalBlocks,
        boolean truncated,
        List<PrecedingBlockView> blocks
) {

    /**
     * 一个内容块。
     *
     * @param authorName 谁写的。给它是为了<b>风格衔接</b>——下一棒该知道前面是谁的手笔；
     *                   而这本来就是文章公开可见的信息，不泄漏任何东西
     * @param sourceTool 用什么工具写的（claude-code / codex …）
     */
    public record PrecedingBlockView(
            int displayOrder,
            String authorType,
            String authorName,
            String sourceTool,
            String content
    ) {}
}
