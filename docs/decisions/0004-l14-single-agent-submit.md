# ADR-0004 · 单机娘 Skill 自动投稿（submit-single + AgentContribution + 草稿 URL）

- **状态**：设计简报待审（2026-07-25 · L14 设计简报① · 导师带练，交主人审思路后施工）
- **关联**：消费 agent 令牌（[[0003-l13-agent-assume-chain3]] Chain 3）；镜像 owner 投稿链路（`ContentService.createOwnerDraft`）；`contribution` 表 agent 字段（V005 已备，零迁移）；[[teaching-methodology]]

## 背景 / 问题
L13 建成四层认证与 Chain 3（`/agent/**`），机娘拿到 AgentActingToken 却还没有**任何真实业务能力**——`/agent/whoami` 只是试金石。L14 要让机娘把一段开发过程写成**草稿**投进 content，返回草稿 URL 给主人，但**机娘绝不能自己发布**（发布权永远属主人）。这是 Chain 3 的第一个真实业务消费者。

## 考古结论（契约冲突定性 · 本课关键取证）
探查发现活契约 `docs/api/agentlog-openapi.yaml` 里唯一的 agent submit 是 `POST /agent/contribution-tickets/{ticketCode}/contributions`（`SubmitContributionResponse` 返 `ticketCode`/`ticketStatus`/`nextHandoffToken`），全是 **L15/L16 多机娘 ACPP 协作**概念——而 L14 验收线恰是"不写多机娘接力"。

**定性**：这不是"L14 与后续课程冲突"，而是**活契约漏登记了 L14 的独立端点**。三份更权威的文档一致证明 L14 单机娘投稿是独立用例：
- `用例到代码矩阵.md`：把"单机娘投稿 → CLI `submit-single` → `SubmitSingleContributionService` → 关键表 draft"与协作"submit → `SubmitContributionService` → 幂等"**列为两个独立用例**。
- `范围与阶段.md`：阶段 B 含"单机娘 Skill 投稿"，阶段 C 才是 ACPP。
- `course_schedule.csv` L14 `scope=submit-single`、`forbidden=不写多机娘接力`。

活契约把 submit 只落了协作版（L16），漏画单机娘版——与 D-11(L10 缺席)/D-01(契约漏 email 端点)同类：契约作为活文档漏记权威蓝图。处置同前例：**以权威蓝图为准，补画端点 + 登记 DRIFT**。SKILL.md 蓝图只写 `collab submit` 亦是同一认知惯性（把"单机娘"当"一个人的协作"），本课按 course_schedule 拆出独立路径。

## 安全不变量（非岔路 · 钉死）
- **机娘无 publish**：`/agent/**` 下只暴露建草稿，**绝无 publish 端点**（course_schedule 硬验收线「Agent 无 publish」）。发布权只在 owner 的 `POST /owner/drafts/{id}/publish`。
- **草稿归属背后的主人**：agent 建的草稿 `draft.owner_user_id` 填 `AgentPrincipal.ownerUserId`（机娘背后的主人），天然对齐既有租户隔离——主人才能在 `/owner/drafts/{id}` 看到、审、发这篇稿。
- **忠实落库作者维度**：`contribution.author_type=AGENT` + `author_agent_id`/`source_tool`/`client_run_id`（全部来自 AgentPrincipal，V005 已备字段）。

## 决策
1. **端点** `POST /api/v1/agent/drafts`（**Chain 3 · agent 令牌保护**），body `CreateAgentDraftRequest{title, channelId, content, summary?}`，返 `DraftView` + 草稿 URL。**无 publish 端点**。
2. **Service 复用**：`ContentService` 抽私有内核 `createDraftInternal(...)`（建 Post + Contribution + Draft + DraftBlock，作者维度参数化）；`createOwnerDraft` 改薄封装；新增 `createAgentDraft(AgentPrincipal, CreateAgentDraftRequest)`。对齐用例矩阵命名可另起 `SubmitSingleContributionService` 或就地扩 ContentService（施工时定，倾向就地扩，避免 Service 碎片化）。
3. **草稿 URL** = `{agentlog.web.base-url}/#/owner/drafts/{draftId}`（主人审稿预览路径 · 可配 base-url，仿 L12 `verification-uri`）。base-url 走 `application.yml` 配置 + 环境变量覆盖。
4. **AI 内容标识本课不碰**（主人拍板）：`contribution.author_type=AGENT` 忠实落库即可；发布时 `content_origin` 的 HUMAN_ONLY→AI_* 推导留 **L20 主人审稿**课。现有 publish 写死 HUMAN_ONLY 不动。
5. **CLI** `agentlog submit --file <path> [--title --channel --summary]`（简洁命名 + 对齐蓝图的 `--file` 读证据文件），带 agent 令牌调 `/agent/drafts`，stdout 机器可读 JSON 含 `draftUrl`，**绝不打印 token**（沿用 L13 铁律）。
6. **skill/agentlog**：按 Pack 蓝图**裁出单机娘部分**——`SKILL.md`（Preconditions→assume→收集证据→`submit`→返回草稿 URL→提醒主人审稿；**剔除所有 `collab start/join/wait/claim-turn`**，那是 L15）+ `scripts/agentlog.sh|ps1` wrapper（`exec agentlog "$@"`）+ 裁剪版 `references/`（contribution-template + evidence-policy + error-actions，去协作项）。
7. **零迁移**：`ContributionDO` 的 `authorAgentId`/`sourceTool`/`clientRunId` 在 V005 已建（注释标"L15+ 用"，本课按 course_schedule 提前到 L14 启用，注释同步更正）。

## 备选与权衡
- **复用契约现有的协作 submit（绑 ticket）** → 弃：违背"不写多机娘接力"，且 ticket/handoff 是 L15/L16 概念，L14 提前引入 = 过度设计 + 打乱课程编排。
- **单入口 `POST /drafts` 靠令牌类型分流作者（从零抽象）** → 弃：要重构已冻结的 owner 端点(L06)，把认证关注点漏进业务层；抽象价值要到 L15 多机娘接力才显现，现在抽 = 猜未来。URL 前缀分流(`/agent/**` vs `/owner/**`)正是 L13 三链的设计意图，镜像即兑现。
- **草稿 URL 指向公开帖** → 弃：草稿未发布无公开页；指向 owner 审稿预览路径才对。

## 后果
- ⚠️ **冻结面 → 待登记 DRIFT D-13**：新端点 `POST /api/v1/agent/drafts`（活契约原无单机娘 submit，只有协作版）；活契约需补画该 path + `CreateAgentDraftRequest` schema，回写 Pack `03-api`。无新错误码、无新迁移、无模块边界变化（content 自写自表，D-05 确认不需 Facade）。
- 前端需补**草稿预览页**（`/owner/drafts/:id`）承接草稿 URL——course_schedule L14 `files` 未列 web，但主人拍板的"草稿 URL 指向预览路径"要求前端有承接页，列入④前端环节。
- `AuthorType.AGENT`/`ContributionDO` agent 字段的"L15+"注释更正为"L14 启用"。
