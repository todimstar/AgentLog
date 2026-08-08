package com.agentlog.content;

/**
 * content 模块的<b>对外写入入口</b> —— 跨模块写的唯一正路（L16 · ADR-0006 · DRIFT D-16）。
 *
 * <h3>为什么直到 L16 才出现</h3>
 * 本项目从 L02 起就把边界交给 Spring Modulith 强制：<b>模块根包 = 公开 API，子包 = 私有</b>
 * （{@code ModularityTest#verifiesModuleStructure} 用 ArchUnit 静态校验，违规即红）。
 * 在此之上还有一条实装铁律（Pack {@code 16-codex/DRIFT-REGISTER.md} D-05）：
 * <pre>
 *   跨模块【只读】→ 走 SQL 投影直查物理表（如 forum 的 FeedService 直查 user_account）
 *   跨模块【写】  → 只碰本模块自己的表
 * </pre>
 * 前 15 课没有任何一个场景需要跨模块写，所以这条 Facade 一直没被造出来 ——
 * {@code content/package-info.java} 从 L02 起就写着「对外入口规划为 ContentFacade」，是一张挂了 14 课的空头支票。
 *
 * <p>L16 的 submit 第一次把它逼出来了：collaboration 模块要往 content 的四张表
 * （post / contribution / draft / draft_block）写数据，而且必须<b>同事务</b>——
 * 机娘提交完要立刻拿到 draftUrl 回给主人，异步事件模型对不上这个同步返回。
 *
 * <h3>为什么是接口而不是直接调 ContentService</h3>
 * 依赖倒置：collaboration 只看得见根包的这个接口，看不见 {@code content.application.ContentService}
 * （私有子包，import 即 ModularityTest 红）。于是：
 * <ul>
 *   <li>content 可以随意重构内部实现，collaboration 不受影响；</li>
 *   <li>collaboration 能写什么，<b>完全由这个接口的方法签名限定</b> ——
 *       它拿不到任何 Mapper，也就不可能绕过 content 的业务规则去改表。</li>
 * </ul>
 * 与 L14 的 {@code AgentIdentity}（shared 里的只读身份接口）是同一手法的两侧：那次解决跨模块<b>读</b>，这次解决<b>写</b>。
 */
public interface ContentFacade {

    /**
     * 把机娘的一段贡献追加进一篇协作草稿。<b>首棒与后续棒共用这一个方法</b>，由
     * {@link AppendCommand#draftId()} 是否为 null 区分：
     * <pre>
     *   draftId == null  → 首棒：创建 post + contribution + draft + draft_block（复用 L06 的建草稿内核）
     *   draftId != null  → 后续棒：只追加 contribution + draft_block（display_order 顺延）
     * </pre>
     *
     * <p>★ 为什么首棒才建草稿（不是在 collab start 时就建）：
     * {@code APPROVAL_RECORD.md} 有一条主人签字冻结的产品规则 ——「<b>首棒失败不暴露空草稿</b>」。
     * 提前建就会留下孤儿空草稿，只能靠"零块过滤"藏起来；而「不暴露」的本意是<b>不存在</b>而非<b>藏起来</b>。
     * 不建那一行，任何读路径都查不到它 —— 不变量由<b>数据的存在性</b>保证，
     * 而不是由每个查询都记得过滤来保证。
     *
     * <p>★ 事务：本方法不自己开事务边界，跟随调用方（collaboration 的 submit）的事务。
     * 于是「贡献写进 content」与「席位落 DONE」要么一起成功、要么一起回滚，不可能只成一半。
     */
    AppendResult appendAgentContribution(AppendCommand command);

    /**
     * 追加贡献的入参。
     *
     * <p>注意本 record <b>刻意不包含</b> draftUrl 之类的表现层概念：
     * Facade 只管数据，URL 怎么拼是调用方 Controller 的事（webBaseUrl 是它的配置）。
     *
     * @param draftId         已有草稿 id；<b>null 表示首棒</b>，此时 title/channelId 必填
     * @param ownerUserId     机娘背后的主人（草稿归属键，多租户锚点）
     * @param agentAccountId  作者机娘
     * @param sourceTool      来源工具（claude-code / codex …），落 contribution.source_tool
     * @param clientRunId     本次运行 id（隔离键）
     * @param title           首棒建草稿用（来自 session.planned_title）
     * @param channelId       首棒建草稿用（来自 session.planned_channel_id）
     * @param summary         首棒建草稿用，可空
     * @param content         这一棒的正文
     * @param sessionId       回填 contribution.session_id —— V005 预留、V012 建了外键，L16 第一次真正写值
     * @param ticketId        回填 contribution.ticket_id，受 V013 的 uk_contribution_ticket 保护（一票一贡献）
     */
    record AppendCommand(
            Long draftId,
            Long ownerUserId,
            Long agentAccountId,
            String sourceTool,
            String clientRunId,
            String title,
            Long channelId,
            String summary,
            String content,
            Long sessionId,
            Long ticketId) {
    }

    /**
     * 追加结果。返回 id 而非视图对象 —— 调用方（collaboration）要的是「写进哪儿了」，
     * 不是 content 的展示模型；把 DraftView 暴露出去等于把 content 的内部 DTO 变成跨模块契约。
     *
     * @param displayOrder 这一段正文在草稿里的位置（首棒 0，后续顺延）。用于回执与调试
     */
    record AppendResult(
            Long postId,
            Long draftId,
            Long contributionId,
            Long blockId,
            int displayOrder) {
    }
}
