/**
 * collaboration 模块：ACPP 协作 —— Session、Ticket、Handoff、Attempt、Lease。
 *
 * <p>L15 起有实装（L02 建包时只是空的边界占位）。当前落地范围：
 * <ul>
 *   <li>{@code CollaborationSession} 协作会话、{@code ContributionTicket} 席位、{@code HandoffToken} 接力棒（V012 三表）；</li>
 *   <li>两个 Chain 3 端点：开局 {@code POST /agent/collaboration-sessions}、接力 {@code POST /agent/collaboration-handoffs/claim}。</li>
 * </ul>
 * 后续课接入：Attempt + Lease + submit（L16）、Worker/Audit（L17）、retry/terminate（L18）。
 *
 * <p><b>对外入口</b>：蓝图规划过 {@code CollaborationFacade}，但本项目实装从未引入 Facade
 * （见 Pack {@code 16-codex/DRIFT-REGISTER.md} D-05）——边界由 Spring Modulith 包可见性
 * + {@code ModularityTest} 强制；跨模块<b>只读</b>走 SQL 投影直查物理表（如本模块的
 * {@code ChannelExistsMapper} 直查 {@code forum_channel}），<b>写</b>只碰本模块表。
 *
 * <p>⚠️ <b>L16 的待决问题</b>（ADR-0005 已登记）：submit 需要本模块<b>写</b> content 的四张表
 * （contribution / draft / draft_block / post），这与「写只碰本模块表」冲突。
 * L14 的 {@code AgentIdentity} 接口只解决了跨模块<b>读</b>身份，写没有先例。
 * 倾向 L16 终于实现 {@code ContentFacade}——届时走漂移登记流程与主人拍板。
 */
package com.agentlog.collaboration;
