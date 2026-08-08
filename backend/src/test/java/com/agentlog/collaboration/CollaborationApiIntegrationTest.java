package com.agentlog.collaboration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentlog.support.AuthTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * L15 ACPP 开局与接力的 API 验收（Chain 3 · /agent/collaboration-**）。
 *
 * 验收映射（course_schedule L15 / ADR-0005）：
 *   startCreatesSessionTicketAndTailToken → 开局落库：session OPEN + planned_* + 首棒 READY_TO_WRITE + 尾令牌 AVAILABLE
 *   startStoresOnlyDigestNotPlaintext     → 明文只回一次，库里只有摘要
 *   startDerivesAgentDimensionFromToken   → 作者维度来自令牌，客户端传不进来
 *   startRejectsUnknownChannel            → 404 CHANNEL_NOT_FOUND（语义层报错，不靠外键抛约束异常）
 *   startRejectsWithoutAgentToken         → 401
 *   startRejectsOwnerTokenOnAgentChain    → owner 令牌打 /agent/** → 401（链隔离）
 *   joinConsumesTokenAndEnqueuesTicket    → ★「两个对话可排队」：第二棒 WAITING_PREDECESSOR + 因果链成型 + 换新尾令牌
 *   joinRejectsReplayedToken              → ★「同 token 双抢一成功」的串行版：重放得 409，且【不多建票】
 *   joinRejectsExpiredToken               → 410（过期靠 WHERE 惰性判定，不依赖 Worker）
 *   joinRejectsFrozenToken                → 409
 *   joinRejectsUnknownToken               → 404
 *   joinRejectsCrossTenantToken           → ★ 跨主人一律 404（不是 403，防资源枚举）
 *
 * 黑盒：owner 令牌走真实设备授权流铸出，再 assume 出 agent 令牌（套路对齐 L13/L14 的集成测试）。
 * 真并发的「双抢一成功」在 {@link HandoffConcurrencyIntegrationTest}——那个才是本课皇冠。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CollaborationApiIntegrationTest {

    // ⚠️ withUrlParam("serverTimezone","UTC")：让测试连接与【主应用 application.yml 的 datasource URL】
    //    用同一套时区契约。不加的话 Testcontainers 自建 URL 没有这个参数，驱动会按 JVM 本地时区(UTC+8)
    //    写 DATETIME，而容器里的 MySQL NOW(3) 是 UTC —— 两者差 8 小时。
    //    本课的消费路径已改用应用时钟规避（见 HandoffTokenMapper.xml），但 expires_at 的绝对值仍要正确，
    //    否则 L17 的清理 Worker（蓝图里是 WHERE lease_expires_at < NOW(3)）会扫错。
    //    storedExpiryAgreesWithDatabaseClock 就是盯这件事的探针。
    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.0").withUrlParam("serverTimezone", "UTC");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry reg) {
        reg.add("spring.data.redis.host", redis::getHost);
        reg.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    private static int seq = 0;

    // ——————————————————— 开局 ———————————————————

    /** 开局：三张表各落一行，且状态、因果链起点、链尾指针全部正确。 */
    @Test
    void startCreatesSessionTicketAndTailToken() throws Exception {
        Fixture f = setup("start");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-start");

        MvcResult res = mockMvc.perform(post("/api/v1/agent/collaboration-sessions")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"图片模块复盘\",\"channelId\":" + f.channelId
                                + ",\"summary\":\"L15 开局\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postTicket").exists())
                .andExpect(jsonPath("$.contributionTicket.sequenceNo").value(1))
                // 首棒无前序 → 直接可写（不是 WAITING_PREDECESSOR）
                .andExpect(jsonPath("$.contributionTicket.status").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.contributionTicket.requiredAgentId").value(f.agentId.intValue()))
                .andExpect(jsonPath("$.nextHandoffToken").exists())
                // ★ L16 改（DRIFT D-16）：接力棒默认【永不过期】，故本字段为 null。
                //   「能不能再来人」没有时间维度的需求，只有生命周期维度的需求——
                //   由发布（TX-02 第 11 步吊销尾令牌）与终止（L18）来终结，不由时钟。
                //   判据：独占（lease）必须有期限，资格（handoff）不必有期限。
                .andExpect(jsonPath("$.nextHandoffExpiresAt").doesNotExist())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());

        // 对外标识不可枚举：前缀 + 16 位 hex（详见 TicketCodes 的设计说明）。
        Assertions.assertThat(body.get("postTicket").asText()).matches("PT-[0-9a-f]{16}");
        Assertions.assertThat(body.get("contributionTicket").get("ticketCode").asText())
                .matches("CT-[0-9a-f]{16}");

        // 会话：OPEN + planned_* 暂存 + 链尾已指向刚签发的令牌。
        Map<String, Object> session = jdbcTemplate.queryForMap(
                "SELECT status, owner_user_id, planned_title, planned_channel_id, planned_summary, "
                        + "post_id, draft_id, tail_handoff_token_id, last_completed_sequence "
                        + "FROM collaboration_session WHERE post_ticket = ?",
                body.get("postTicket").asText());
        Assertions.assertThat(session.get("status")).isEqualTo("OPEN");
        Assertions.assertThat(((Number) session.get("owner_user_id")).longValue()).isEqualTo(f.ownerUserId);
        Assertions.assertThat(session.get("planned_title")).isEqualTo("图片模块复盘");
        Assertions.assertThat(((Number) session.get("planned_channel_id")).longValue()).isEqualTo(f.channelId);
        Assertions.assertThat(session.get("planned_summary")).isEqualTo("L15 开局");
        Assertions.assertThat(session.get("last_completed_sequence")).isEqualTo(0);
        // ★ 关键不变量：本课【不】创建 post/draft ——「首棒失败不暴露空草稿」。
        Assertions.assertThat(session.get("post_id")).isNull();
        Assertions.assertThat(session.get("draft_id")).isNull();
        Assertions.assertThat(session.get("tail_handoff_token_id")).isNotNull();

        // 尾令牌：AVAILABLE、指向首棒、尚未被消费。
        Map<String, Object> token = jdbcTemplate.queryForMap(
                "SELECT status, owner_user_id, consumed_by_agent_id, consumed_ticket_id, consumed_at "
                        + "FROM handoff_token WHERE id = ?", session.get("tail_handoff_token_id"));
        Assertions.assertThat(token.get("status")).isEqualTo("AVAILABLE");
        Assertions.assertThat(((Number) token.get("owner_user_id")).longValue()).isEqualTo(f.ownerUserId);
        Assertions.assertThat(token.get("consumed_by_agent_id")).isNull();
        Assertions.assertThat(token.get("consumed_ticket_id")).isNull();
        Assertions.assertThat(token.get("consumed_at")).isNull();
    }

    /** 明文令牌只在响应里出现一次；库里只有 HMAC 摘要，反查不到明文。 */
    @Test
    void startStoresOnlyDigestNotPlaintext() throws Exception {
        Fixture f = setup("digest");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-digest");

        MvcResult res = mockMvc.perform(post("/api/v1/agent/collaboration-sessions")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"摘要存储\",\"channelId\":" + f.channelId + "}"))
                .andExpect(status().isCreated()).andReturn();
        String rawToken = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("nextHandoffToken").asText();

        // 明文格式符合 security-blueprint 的约定前缀。
        Assertions.assertThat(rawToken).startsWith("handoff_");
        // 库里任何一行的摘要都不等于明文的字节——即脱库拿不到可用的令牌。
        Integer plaintextRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM handoff_token WHERE token_digest = ?", Integer.class,
                (Object) rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Assertions.assertThat(plaintextRows).isZero();
        // 摘要固定 32 字节（HMAC-SHA256）。
        Integer digestLen = jdbcTemplate.queryForObject(
                "SELECT OCTET_LENGTH(token_digest) FROM handoff_token ORDER BY id DESC LIMIT 1", Integer.class);
        Assertions.assertThat(digestLen).isEqualTo(32);
    }

    /** 作者维度（谁、什么工具、哪次运行）全部由服务端从 agent 令牌派生，请求体无从伪造。 */
    @Test
    void startDerivesAgentDimensionFromToken() throws Exception {
        Fixture f = setup("dim");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "codex", "run-dim-7");

        MvcResult res = mockMvc.perform(post("/api/v1/agent/collaboration-sessions")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        // 故意在 body 里塞作者维度——它们应当被完全忽略（DTO 里根本没有这些字段）
                        .content("{\"title\":\"维度派生\",\"channelId\":" + f.channelId
                                + ",\"requiredAgentId\":999999,\"sourceTool\":\"forged\"}"))
                .andExpect(status().isCreated()).andReturn();
        String ticketCode = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("contributionTicket").get("ticketCode").asText();

        Map<String, Object> ticket = jdbcTemplate.queryForMap(
                "SELECT required_agent_id, source_tool, client_run_id FROM contribution_ticket "
                        + "WHERE ticket_code = ?", ticketCode);
        Assertions.assertThat(((Number) ticket.get("required_agent_id")).longValue()).isEqualTo(f.agentId);
        Assertions.assertThat(ticket.get("source_tool")).isEqualTo("codex");        // 不是 "forged"
        Assertions.assertThat(ticket.get("client_run_id")).isEqualTo("run-dim-7");
    }

    /** 分区不存在 → 干净的 404，而不是外键抛出的约束异常（语义层的错误在语义层报）。 */
    @Test
    void startRejectsUnknownChannel() throws Exception {
        Fixture f = setup("nochan");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-nochan");

        mockMvc.perform(post("/api/v1/agent/collaboration-sessions")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"没有的分区\",\"channelId\":99999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
    }

    /** 无令牌 → 401（Chain 3 无匿名端点）。 */
    @Test
    void startRejectsWithoutAgentToken() throws Exception {
        Fixture f = setup("noauth");
        mockMvc.perform(post("/api/v1/agent/collaboration-sessions")
                        .contentType("application/json")
                        .content("{\"title\":\"无令牌\",\"channelId\":" + f.channelId + "}"))
                .andExpect(status().isUnauthorized());
    }

    /** owner 令牌打 agent 链 → 401。三条链的 matcher 互不相交，凭证不能跨链使用（对齐 L14 同名用例）。 */
    @Test
    void startRejectsOwnerTokenOnAgentChain() throws Exception {
        Fixture f = setup("crosschain");
        mockMvc.perform(post("/api/v1/agent/collaboration-sessions")
                        .header("Authorization", "Bearer " + f.ownerAccessToken)
                        .contentType("application/json")
                        .content("{\"title\":\"跨链\",\"channelId\":" + f.channelId + "}"))
                .andExpect(status().isUnauthorized());
    }

    // ——————————————————— 接力 ———————————————————

    /** ★ 本课主验收「两个对话可排队」：第二个机娘消费尾令牌 → 入队第二棒 + 因果链成型 + 换发新尾令牌。 */
    @Test
    void joinConsumesTokenAndEnqueuesTicket() throws Exception {
        Fixture f = setup("join");
        Started s = start(f, "claude-code", "run-join-a");

        // 第二个机娘（同一个主人名下的另一只）——模拟主人在另一个 AI 对话里 assume 了别的机娘。
        Long agentB = insertAgent(f.ownerUserId, "Queen-" + seq);
        String tokenB = assume(f.ownerAccessToken, agentB, "codex", "run-join-b");

        MvcResult res = mockMvc.perform(post("/api/v1/agent/collaboration-handoffs/claim")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"handoffToken\":\"" + s.handoffToken + "\"}"))
                .andExpect(status().isCreated())
                // 同一个会话（postTicket 不变）——令牌自带上下文，客户端没传过 sessionId
                .andExpect(jsonPath("$.postTicket").value(s.postTicket))
                .andExpect(jsonPath("$.contributionTicket.sequenceNo").value(2))
                // ★「后序可提前排队，不可提前写」：前序还没 DONE，所以第二棒只能等
                .andExpect(jsonPath("$.contributionTicket.status").value("WAITING_PREDECESSOR"))
                // ★ required_agent_id = 消费令牌的那个机娘（令牌被消费的那一刻实名化）
                .andExpect(jsonPath("$.contributionTicket.requiredAgentId").value(agentB.intValue()))
                // 换发了一根新的悬空尾令牌，且与旧的不同 → 接力可以继续延长
                .andExpect(jsonPath("$.nextHandoffToken").exists())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        Assertions.assertThat(body.get("nextHandoffToken").asText()).isNotEqualTo(s.handoffToken);

        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM collaboration_session WHERE post_ticket = ?", Long.class, s.postTicket);

        // 因果链成型：第二棒的 predecessor 正是第一棒（顺序由链决定，与时间戳无关）。
        Map<String, Object> t2 = jdbcTemplate.queryForMap(
                "SELECT t2.sequence_no, t2.predecessor_ticket_id, t1.id AS first_id, t1.sequence_no AS first_seq "
                        + "FROM contribution_ticket t2 "
                        + "JOIN contribution_ticket t1 ON t1.id = t2.predecessor_ticket_id "
                        + "WHERE t2.session_id = ? AND t2.sequence_no = 2", sessionId);
        Assertions.assertThat(t2.get("first_seq")).isEqualTo(1);

        // 旧令牌：CONSUMED，且消费痕迹三件套齐全（谁用的、换出哪张票、什么时候）。
        Map<String, Object> old = jdbcTemplate.queryForMap(
                "SELECT h.status, h.consumed_by_agent_id, h.consumed_at, ct.sequence_no AS consumed_seq "
                        + "FROM handoff_token h LEFT JOIN contribution_ticket ct ON ct.id = h.consumed_ticket_id "
                        + "WHERE h.session_id = ? AND h.status = 'CONSUMED'", sessionId);
        Assertions.assertThat(old.get("status")).isEqualTo("CONSUMED");
        Assertions.assertThat(((Number) old.get("consumed_by_agent_id")).longValue()).isEqualTo(agentB);
        Assertions.assertThat(old.get("consumed_at")).isNotNull();
        Assertions.assertThat(old.get("consumed_seq")).isEqualTo(2);

        // 链尾指针已换成新令牌，且新令牌指向第二棒。
        Map<String, Object> tail = jdbcTemplate.queryForMap(
                "SELECT h.status, ct.sequence_no AS pred_seq FROM collaboration_session s "
                        + "JOIN handoff_token h ON h.id = s.tail_handoff_token_id "
                        + "JOIN contribution_ticket ct ON ct.id = h.predecessor_ticket_id "
                        + "WHERE s.id = ?", sessionId);
        Assertions.assertThat(tail.get("status")).isEqualTo("AVAILABLE");
        Assertions.assertThat(tail.get("pred_seq")).isEqualTo(2);

        // 会话仍是 OPEN：本课不做 lease/submit，没人真正开始写。
        String sessionStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM collaboration_session WHERE id = ?", String.class, sessionId);
        Assertions.assertThat(sessionStatus).isEqualTo("OPEN");
    }

    /** ★ 重放同一张令牌 → 409，且【库里不多出一张票】（一次性消费的串行验证）。 */
    @Test
    void joinRejectsReplayedToken() throws Exception {
        Fixture f = setup("replay");
        Started s = start(f, "claude-code", "run-replay-a");
        Long agentB = insertAgent(f.ownerUserId, "Replay-" + seq);
        String tokenB = assume(f.ownerAccessToken, agentB, "codex", "run-replay-b");

        mockMvc.perform(post("/api/v1/agent/collaboration-handoffs/claim")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"handoffToken\":\"" + s.handoffToken + "\"}"))
                .andExpect(status().isCreated());

        // 第二次用同一张令牌：409 + 明确告诉客户端该去索取最新尾令牌。
        mockMvc.perform(post("/api/v1/agent/collaboration-handoffs/claim")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"handoffToken\":\"" + s.handoffToken + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACPP_HANDOFF_CONSUMED"));

        // ★ 关键：席位总数仍是 2（首棒 + 一张接力棒换来的票），没有因为重放多出第三张。
        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM collaboration_session WHERE post_ticket = ?", Long.class, s.postTicket);
        Integer ticketCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contribution_ticket WHERE session_id = ?", Integer.class, sessionId);
        Assertions.assertThat(ticketCount).isEqualTo(2);
    }

    /**
     * 过期令牌 → 410。
     *
     * ★ 这条测试同时证明了「过期判定不依赖清理 Worker」：
     *   我们只把 expires_at 改成过去，**故意不动 status（仍是 AVAILABLE）**——
     *   模拟 L17 的清理 Worker 还没来得及把它标成 EXPIRED 的那个窗口。
     *   消费依然必须失败，因为判定写在原子 UPDATE 的 WHERE 里。
     */
    @Test
    void joinRejectsExpiredToken() throws Exception {
        Fixture f = setup("expired");
        Started s = start(f, "claude-code", "run-exp-a");
        Long agentB = insertAgent(f.ownerUserId, "Exp-" + seq);
        String tokenB = assume(f.ownerAccessToken, agentB, "codex", "run-exp-b");

        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM collaboration_session WHERE post_ticket = ?", Long.class, s.postTicket);
        jdbcTemplate.update(
                "UPDATE handoff_token SET expires_at = ? WHERE session_id = ? AND status = 'AVAILABLE'",
                Instant.now().minus(1, ChronoUnit.HOURS), sessionId);

        mockMvc.perform(post("/api/v1/agent/collaboration-handoffs/claim")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"handoffToken\":\"" + s.handoffToken + "\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ACPP_HANDOFF_EXPIRED"));
    }

    /** 冻结的令牌（L17 前序失败时会这么标）→ 409，客户端应停止并报告主人。 */
    @Test
    void joinRejectsFrozenToken() throws Exception {
        Fixture f = setup("frozen");
        Started s = start(f, "claude-code", "run-frz-a");
        Long agentB = insertAgent(f.ownerUserId, "Frz-" + seq);
        String tokenB = assume(f.ownerAccessToken, agentB, "codex", "run-frz-b");

        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM collaboration_session WHERE post_ticket = ?", Long.class, s.postTicket);
        jdbcTemplate.update(
                "UPDATE handoff_token SET status = 'FROZEN' WHERE session_id = ? AND status = 'AVAILABLE'",
                sessionId);

        mockMvc.perform(post("/api/v1/agent/collaboration-handoffs/claim")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"handoffToken\":\"" + s.handoffToken + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACPP_HANDOFF_FROZEN"));
    }

    /** 压根不存在的令牌 → 404（不泄漏"这串字符是不是某个真令牌"）。 */
    @Test
    void joinRejectsUnknownToken() throws Exception {
        Fixture f = setup("unknown");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "codex", "run-unknown");

        mockMvc.perform(post("/api/v1/agent/collaboration-handoffs/claim")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"handoffToken\":\"handoff_this-token-never-existed\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACPP_HANDOFF_NOT_FOUND"));
    }

    /**
     * ★ 多租户强制门禁：用户 B 的机娘拿用户 A 的令牌 → 404（**不是 403**）。
     *
     * 为什么必须是 404：403 等于承认"这东西存在、只是不给你"，攻击者据此就能拿一堆字符串
     * 来探测哪些是真令牌。统一 404 让"不存在"和"不属于你"在观测上不可区分。
     * 见 Pack 09-security/多租户授权与行级隔离.md §5、§6（每个 owner 端点都要补跨用户越权用例）。
     */
    @Test
    void joinRejectsCrossTenantToken() throws Exception {
        Fixture alice = setup("tenantA");
        Started s = start(alice, "claude-code", "run-tenant-a");

        // 另一个完全独立的主人 + 他自己的机娘。
        Fixture bob = setup("tenantB");
        String bobAgentToken = assume(bob.ownerAccessToken, bob.agentId, "codex", "run-tenant-b");

        mockMvc.perform(post("/api/v1/agent/collaboration-handoffs/claim")
                        .header("Authorization", "Bearer " + bobAgentToken)
                        .contentType("application/json")
                        .content("{\"handoffToken\":\"" + s.handoffToken + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACPP_HANDOFF_NOT_FOUND"));

        // 且【无副作用】：alice 的令牌仍然可用（没被 bob 的失败尝试改动分毫）。
        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM collaboration_session WHERE post_ticket = ?", Long.class, s.postTicket);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM handoff_token WHERE session_id = ? ORDER BY id DESC LIMIT 1",
                String.class, sessionId);
        Assertions.assertThat(status).isEqualTo("AVAILABLE");
    }

    /**
     * ★ L16 改（DRIFT D-16）：接力棒默认<b>不签发过期时刻</b>。
     *
     * <p>原测试名 {@code storedExpiryAgreesWithDatabaseClock}，是一条时区口径探针：
     * Java 写进去的 expires_at，用数据库自己的时钟看也该是「约 24 小时后」。
     * L16 把 handoff 的 TTL 默认关掉之后，这里没有 expires_at 可量了，
     * <b>探针搬到了仍然必须有 TTL 的那一侧</b>——见
     * {@code LeaseAndSubmitIntegrationTest#leaseExpiryAgreesWithDatabaseClock}（量 lease_expires_at）。
     * 那正是 L17 清理 Worker 要扫的列（{@code WHERE lease_expires_at < NOW(3)}），盯它比盯这里更对症。
     *
     * <p>本测试保留下来，守的是新的不变量：<b>默认配置下接力棒永不过期</b>。
     * 若哪天有人把 {@code agentlog.token.handoff-ttl} 又配回去而没走漂移流程，这里会先红。
     */
    @Test
    void tailTokenHasNoExpiryByDefault() throws Exception {
        Fixture f = setup("tz");
        Started s = start(f, "claude-code", "run-tz");

        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM collaboration_session WHERE post_ticket = ?", Long.class, s.postTicket);
        Map<String, Object> token = jdbcTemplate.queryForMap(
                "SELECT status, expires_at FROM handoff_token "
                        + "WHERE session_id = ? AND status = 'AVAILABLE'", sessionId);

        Assertions.assertThat(token.get("expires_at"))
                .as("接力棒默认永不过期（D-16）：资格不必有期限，因为它不挡任何人。"
                        + "终结它的是发布/终止时的吊销，不是时钟")
                .isNull();
        // 惰性判定的另一半仍然有效：配了 TTL 时照样拦得住过期令牌 —— 见 joinRejectsExpiredToken，
        // 那条测试手动把 expires_at 改到过去，验证消费路径的 WHERE 仍然会拒绝。
        Assertions.assertThat(token.get("status")).isEqualTo("AVAILABLE");
    }

    // —— 辅助 ——

    /** 开局一次，返回 postTicket 与尾令牌明文。 */
    private Started start(Fixture f, String tool, String runId) throws Exception {
        String agentToken = assume(f.ownerAccessToken, f.agentId, tool, runId);
        MvcResult res = mockMvc.perform(post("/api/v1/agent/collaboration-sessions")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"接力验收\",\"channelId\":" + f.channelId + "}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        return new Started(body.get("postTicket").asText(), body.get("nextHandoffToken").asText());
    }

    /** 建 channel + 铸 owner 令牌 + 建机娘（套路对齐 L14 的 AgentDraftIntegrationTest）。 */
    private Fixture setup(String tag) throws Exception {
        String suffix = tag + (++seq);
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO forum_channel(slug,name,sort_order,enabled,post_count,created_at,updated_at) "
                        + "VALUES (?,?,0,TRUE,0,?,?)", "ch-" + suffix, "开发日志", now, now);
        Long channelId = jdbcTemplate.queryForObject(
                "SELECT id FROM forum_channel ORDER BY id DESC LIMIT 1", Long.class);

        String email = "collab-" + suffix + "@example.com";
        MvcResult pairResult = mockMvc.perform(post("/api/v1/cli/device-pairings")
                        .contentType("application/json")
                        .content("{\"installationCode\":\"inst-" + suffix + "\",\"deviceName\":\"dev\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode pair = objectMapper.readTree(pairResult.getResponse().getContentAsString());

        MockHttpSession session = AuthTestSupport.insertUserAndLogin(
                mockMvc, jdbcTemplate, passwordEncoder, "collab-" + suffix, email);
        mockMvc.perform(post("/api/v1/web/device-pairings/confirm").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"userCode\":\"" + pair.get("userCode").asText() + "\"}"))
                .andExpect(status().isNoContent());

        MvcResult tokenResult = mockMvc.perform(post("/api/v1/cli/device-pairings/token")
                        .contentType("application/json")
                        .content("{\"deviceCode\":\"" + pair.get("deviceCode").asText() + "\"}"))
                .andExpect(jsonPath("$.status").value("APPROVED")).andReturn();
        String ownerAccessToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("ownerAccessToken").asText();

        Long ownerUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE email=?", Long.class, email);
        Long agentId = insertAgent(ownerUserId, "星梦-" + suffix);
        return new Fixture(ownerAccessToken, ownerUserId, agentId, channelId);
    }

    private String assume(String ownerToken, Long agentId, String tool, String runId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cli/agents/" + agentId + "/assume")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content("{\"sourceTool\":\"" + tool + "\",\"clientRunId\":\"" + runId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("agentActingToken").asText();
    }

    private Long insertAgent(Long ownerUserId, String nickname) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO agent_account(owner_user_id,nickname,status,created_at,updated_at) "
                        + "VALUES (?,?,'ACTIVE',?,?)", ownerUserId, nickname, now, now);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM agent_account WHERE owner_user_id=? AND nickname=?",
                Long.class, ownerUserId, nickname);
    }

    private record Fixture(String ownerAccessToken, Long ownerUserId, Long agentId, Long channelId) {
    }

    private record Started(String postTicket, String handoffToken) {
    }
}
