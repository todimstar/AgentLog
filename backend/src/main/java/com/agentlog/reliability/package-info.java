/**
 * reliability 模块：Worker（超时扫描）、Redis 限流。
 *
 * <p>L17 落地内容：
 * <ul>
 *   <li>{@code ExpiredAttemptWorker}（每 30s 扫过期 attempt，SKIP LOCKED 防多实例重复）；</li>
 *   <li>{@code ExpireAttemptService}（单条处理，独立事务防毒丸）；</li>
 *   <li>{@code WorkerDevController}（dev-profile 下手动触发，供 Postman 验收）；</li>
 *   <li>{@code ErrorReportDO} / {@code ErrorReportMapper}（写事故记录）；</li>
 *   <li>Redis 令牌桶限流（{@code RateLimit} 注解 + 切面）。</li>
 * </ul>
 *
 * <p><b>模块边界</b>：对外入口 = {@code ReliabilityFacade}（本课暂无需外部调用，占位）。
 * reliability 可以调用 {@code collaboration}（读写 ticket/session/attempt）以及
 * {@code shared/event}（发布 {@code AttemptExpired}）。collaboration 不回调 reliability。
 */
package com.agentlog.reliability;
