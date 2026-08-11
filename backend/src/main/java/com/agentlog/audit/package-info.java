/**
 * audit 模块：审计时间线（L17）。
 *
 * <p>L17 落地内容：
 * <ul>
 *   <li>{@code AuditListener}：订阅 {@code AttemptExpired} 和 {@code ContributionSubmitted}，
 *       写 audit_record 流水（用 Modulith 发件箱保证不丢）；</li>
 *   <li>{@code AuditRecordDO} / {@code AuditRecordMapper}：流水表读写。</li>
 * </ul>
 *
 * <p><b>模块边界</b>：
 * audit 只消费 {@code shared/event} 里的事件，不主动调用任何业务模块。
 * 对外入口规划为 {@code AuditFacade}（本课暂无跨模块调用，占位）。
 */
package com.agentlog.audit;
