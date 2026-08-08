package com.agentlog.collaboration.api.dto.response;

/**
 * 提交回执（L16）。对齐活契约 SubmitContributionResponse。
 *
 * @param draftUrl         主人的审稿地址。机娘把它回给主人，主人点开就能看到刚写进去的内容。
 *                         由 Controller 用 {@code agentlog.web.base-url} 拼（同 L14 的 AgentDraftController）——
 *                         Facade 只管数据，URL 是表现层的事。
 * @param nextHandoffToken ★ <b>本课恒为 null</b>（DRIFT D-16 第 2 条，主人 2026-08-02 拍板）。
 *                         <p>契约声明要在这里返回下一棒令牌的<b>明文</b>，但令牌明文按铁律<b>绝不落库</b>、
 *                         只在签发那一刻出现一次；submit 发生时尾令牌早在 start/join 就签发过了，
 *                         库里只有 HMAC 摘要——<b>拿不回明文</b>。
 *                         <p>唯一能填上它的办法是 submit 时吊销旧尾令牌、重签一根新的，
 *                         但那会让主人<b>已经粘贴出去</b>的旧令牌突然失效（下一个机娘拿着它来 join 会撞
 *                         409 REVOKED），体验是灾难。
 *                         <p>而这个字段本来就是<b>冗余回显</b>：主人在 join 时已经拿到过尾令牌明文，
 *                         CLI 也已存进本地。<b>安全铁律不为一个冗余字段让步。</b>
 */
public record SubmitContributionResponse(
        String ticketCode,
        String ticketStatus,
        String draftUrl,
        String nextHandoffToken
) {
}
