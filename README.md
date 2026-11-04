# AgentLog

> 面向 AI 编码工具协作的开发日志平台：让多个 Agent 以可追溯、可恢复的方式接力贡献内容，并由人类用户最终审核和发布。

**项目状态：个人实践项目 / 持续开发中。** 当前仓库重点展示 ACPP 协作协议、状态流转、失败恢复及相关集成测试；不是已投产的多租户 AI SaaS，也不直接在后端统一调用 Codex、Claude Code 等大模型 API。

## 为什么做

在使用 Codex、Claude Code 等编码工具开发项目时，不同 AI 会话之间缺少统一的任务接力状态，零散的开发记录也难以沉淀为一篇保留作者来源、过程和审稿记录的日志。

AgentLog 尝试把「多个 Agent 如何有序地贡献一篇文章」建模为可验证的协作过程，而不只是拼接不同模型的回答。

## 一眼看懂 ACPP

```text
协作会话 Session
   └── Ticket #1 ──→ Ticket #2 ──→ Ticket #3   （按前序关系接力）
          │                │
          │                └── Attempt #1 + Lease  （一次执行与限时写入资格）
          │                          ├── 成功：提交内容、唤醒后继
          │                          └── 超时：标记失败、阻塞后继、等待人工处理
          └── 首棒完成后建立草稿
                                      ↓
                             用户审稿 / 发布
```

- **Session**：一次协作及其整体状态。
- **Ticket**：接力链中的写作席位，记录前序关系和目标 Agent。
- **Attempt**：Ticket 的某次实际执行；重试可以产生新的 Attempt。
- **Lease**：附着在 Attempt 上的限时写入资格，提交时由数据库条件更新校验。
- **Handoff Token**：控制下一个 Agent 加入协作链的凭证。

这里的 Agent 指经 CLI、Skill 和服务端认证参与工作的外部 AI 编码工具；目前不意味着服务端能够自行唤醒或调度模型对话。

## 已有实现与设计重点

| 方向 | 关键实现 |
| --- | --- |
| 多 Agent 协作 | Session、Ticket、Handoff Token 和 Attempt 的持久化状态 |
| 执行资格与提交 | Lease 签发、过期判定、条件 UPDATE 与幂等控制 |
| 超时回收 | 定时 Worker、`FOR UPDATE SKIP LOCKED` 扫描、独立事务和最终状态闸门 |
| 失败传播 | Ticket 失败、后序链阻塞、尾令牌冻结、Session 暂停或作废 |
| 人工介入 | 失败后重试、终止协作、草稿审阅等流程 |
| 追溯与审计 | Agent 来源、事件、事故报告与审计记录 |
| 代码质量 | Spring Modulith 模块边界测试、MySQL/Testcontainers 集成测试 |

**边界说明：** 目前的并发测试验证了同一 Attempt 的原子超时宣告；并不等于已经完成两个独立部署实例的完整压测。Lease 心跳/续约字段已预留，但当前版本尚未实现自动续租。

## 技术栈

- **后端：** Java 21、Spring Boot 3、Spring Modulith、Spring Security、MyBatis-Plus、Flyway
- **存储与基础设施：** MySQL 8、Redis、MinIO（S3 兼容）
- **前端：** Vue 3、TypeScript、Vite、Element Plus
- **CLI：** Java / picocli
- **测试：** JUnit、Spring Boot Test、Testcontainers

## 仓库导航

| 目录 / 文件 | 用途 |
| --- | --- |
| [backend/](backend/) | 后端应用、领域模块与集成测试 |
| [cli/](cli/) | AgentLog 命令行客户端 |
| [web/](web/) | Web 前端 |
| [skill/agentlog/](skill/agentlog/) | Agent 接入 Skill 与参考资料 |
| [docs/api/](docs/api/) | OpenAPI 接口描述 |
| [docs/decisions/](docs/decisions/) | 关键架构决策 ADR |
| [infra/](infra/) | 本地开发依赖配置 |
| [backend/src/magic-L17/](backend/src/magic-L17/) | L17：超时 Worker、审计与事件机制的学习讲义 |
| [CHANGELOG.md](CHANGELOG.md) | 按阶段记录的实现过程与纠偏 |

> 这个项目也作为我的 AI 辅助软件工程学习实践，按课次推进。因此仓库保留了 `magic-LXX` 的教学讲义、代码陪读、演示和测试资料。它们是个人学习过程记录，不是生产运行所需组件。

### 从哪里读 ACPP 的超时回收代码？

1. [ExpiredAttemptWorker.java](backend/src/main/java/com/agentlog/reliability/worker/ExpiredAttemptWorker.java)：定时领取候选 Attempt ID。
2. [CollaborationFacade.java](backend/src/main/java/com/agentlog/collaboration/CollaborationFacade.java)：模块边界与事务声明。
3. [ExpiredAttemptScanMapper.xml](backend/src/main/resources/mapper/collaboration/ExpiredAttemptScanMapper.xml)：`SKIP LOCKED` 与超时条件 UPDATE。
4. [ExpireAttemptFacadeImpl.java](backend/src/main/java/com/agentlog/collaboration/application/ExpireAttemptFacadeImpl.java)：`affectedRows` 闸门与回收流程。
5. [FailurePropagation.java](backend/src/main/java/com/agentlog/collaboration/application/FailurePropagation.java)：沿接力链传播失败。
6. [WorkerIntegrationTest.java](backend/src/test/java/com/agentlog/reliability/WorkerIntegrationTest.java)：相关集成测试。

## 本地开发（基本入口）

**前提：** JDK 21、Docker / Docker Compose、Node.js 与 npm；本地数据库等配置见 [infra/](infra/) 和 `backend/src/main/resources/application.yml`。

```bash
# 1. 启动本地依赖（仅供开发，不要直接用于公网生产部署）
docker compose -f infra/docker-compose.local.yml up -d

# 2. 后端（仓库根目录）
./mvnw -pl backend spring-boot:run

# 3. 前端（另开终端）
cd web
npm install
npm run dev
```

后端默认使用 `local` profile。邮件等真实凭据应写入本地忽略文件或环境变量，不要提交。以上是基于仓库配置的**启动入口**，不代表已在全部系统环境上完成端到端安装验证。

测试可使用 `./mvnw -pl backend test`；涉及 Testcontainers 的测试需要可用的 Docker 环境。

## 当前范围与后续计划

项目重点仍在于完善端到端使用体验、联调和部署文档，继续验证并发竞争与失败恢复的边界。部分工作流设计仍处于迭代阶段，请以实际代码和测试为准。

---

*这是一个将 AI 协作过程工程化、可审查化的探索项目。AI 工具参与了编码、排错和文档制作；需求边界、设计决策和验收以项目中的人工审查及测试记录为依据。*
