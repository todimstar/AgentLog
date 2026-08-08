package com.agentlog.collaboration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentlog.support.AuthTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
 * L16 端到端验收：领租约 → 写作 → 提交 → 交棒。
 *
 * 覆盖 {@code course_schedule} L16 的三条硬验收：
 * <pre>
 *   后序等待   → successorBecomesWritableAfterPredecessorSubmits
 *   双领一成功 → 见 {@link LeaseConcurrencyIntegrationTest}（并发必须真并发，单独一类）
 *   submit 幂等 → replayedSubmitReturnsSameResponseWithoutDuplicating
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class LeaseAndSubmitIntegrationTest {

    // ⚠️ withUrlParam("serverTimezone","UTC")：与主应用 datasource URL 用同一套时区契约。
    //    不加则驱动按 JVM 本地时区(UTC+8)写 DATETIME，而容器 MySQL 的 NOW(3) 是 UTC —— 差 8 小时。
    //    L15 被这个咬过一次（DRIFT D-15），本课的 leaseExpiryAgreesWithDatabaseClock 就是站岗探针。
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

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;

    private static int seq = 0;

    // ══════════════════ 主流程 ══════════════════

    /**
     * 首棒：领租约 → 提交 → <b>这一刻才创建 post/draft/contribution/block</b>。
     *
     * ★ 本测试守的核心不变量（APPROVAL_RECORD 签字冻结）：<b>首棒失败不暴露空草稿</b>。
     * 领了租约但还没提交时，草稿必须<b>不存在</b>——不是"存在但被过滤掉"。
     * 不变量由数据的存在性保证，而不是由每个查询都记得过滤来保证。
     */
    @Test
    void firstTurnSubmitCreatesDraftAndBlock() throws Exception {
        Fixture f = setup("first");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-first");
        Started started = start(f, agentToken);

        // —— 领租约 ——
        MvcResult leaseRes = mockMvc.perform(post(leases(started.ticketCode))
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketCode").value(started.ticketCode))
                .andExpect(jsonPath("$.leaseToken").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.context.sequenceNo").value(1))
                .andExpect(jsonPath("$.context.isFirstTurn").value(true))
                .andReturn();
        String leaseToken = json(leaseRes).get("leaseToken").asText();
        Assertions.assertThat(leaseToken).startsWith("lease_");

        // 席位落 LEASED、attempt 建出来、会话推进 RUNNING。
        Assertions.assertThat(ticketStatusInDb(started.ticketCode)).isEqualTo("LEASED");
        Map<String, Object> attempt = jdbcTemplate.queryForMap(
                "SELECT a.status, a.attempt_no, a.lease_token_digest, a.lease_expires_at, t.active_attempt_id "
                        + "FROM contribution_attempt a JOIN contribution_ticket t ON t.id = a.ticket_id "
                        + "WHERE t.ticket_code = ?", started.ticketCode);
        Assertions.assertThat(attempt.get("status")).isEqualTo("ACTIVE");
        Assertions.assertThat(attempt.get("attempt_no")).isEqualTo(1);
        Assertions.assertThat(attempt.get("lease_expires_at")).isNotNull();
        Assertions.assertThat(attempt.get("active_attempt_id")).isNotNull();
        Assertions.assertThat(sessionColumn(started.postTicket, "status")).isEqualTo("RUNNING");

        // ★ 关键：此刻草稿还【不存在】。
        Assertions.assertThat(sessionColumn(started.postTicket, "draft_id")).isNull();
        Assertions.assertThat(sessionColumn(started.postTicket, "post_id")).isNull();

        // —— 提交 ——
        mockMvc.perform(submit(started.ticketCode, agentToken, leaseToken, "第一棒：先讲清楚问题。"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketStatus").value("DONE"))
                .andExpect(jsonPath("$.draftUrl").exists())
                // ★ 恒为 null（D-16 第 2 条）：明文不落库，submit 拿不回尾令牌明文。
                .andExpect(jsonPath("$.nextHandoffToken").doesNotExist());

        // 现在草稿才出现，且带着协作溯源。
        Object draftId = sessionColumn(started.postTicket, "draft_id");
        Assertions.assertThat(draftId).isNotNull();
        Assertions.assertThat(sessionColumn(started.postTicket, "post_id")).isNotNull();
        Assertions.assertThat(sessionColumn(started.postTicket, "status")).isEqualTo("AWAITING_CONTINUATION");
        Assertions.assertThat(sessionColumn(started.postTicket, "last_completed_sequence")).isEqualTo(1);
        Assertions.assertThat(ticketStatusInDb(started.ticketCode)).isEqualTo("DONE");

        Map<String, Object> contribution = jdbcTemplate.queryForMap(
                "SELECT c.author_type, c.author_agent_id, c.source_tool, c.raw_content, c.session_id, c.ticket_id "
                        + "FROM contribution c JOIN contribution_ticket t ON t.id = c.ticket_id "
                        + "WHERE t.ticket_code = ?", started.ticketCode);
        Assertions.assertThat(contribution.get("author_type")).isEqualTo("AGENT");
        Assertions.assertThat(((Number) contribution.get("author_agent_id")).longValue()).isEqualTo(f.agentId);
        Assertions.assertThat(contribution.get("source_tool")).isEqualTo("claude-code");
        Assertions.assertThat(contribution.get("raw_content")).isEqualTo("第一棒：先讲清楚问题。");
        // ★ V005 预留、V012 建外键、L16 第一次真正写值的两列。
        Assertions.assertThat(contribution.get("session_id")).isNotNull();
        Assertions.assertThat(contribution.get("ticket_id")).isNotNull();

        Assertions.assertThat(blockCount(draftId)).isEqualTo(1);
    }

    /**
     * ★「后序等待」验收 + 二棒 append。
     *
     * 第二个机娘在 join 那一刻就拿到了票，但只能排队不能写（WAITING_PREDECESSOR）；
     * 前一棒 submit 之后它被唤醒成 READY_TO_WRITE —— 这就是 CLI {@code collab wait} 等的那一刻。
     * 然后它的 submit <b>不再新建草稿</b>，只往同一篇里 append 第二块正文。
     */
    @Test
    void successorBecomesWritableAfterPredecessorSubmits() throws Exception {
        Fixture f = setup("relay");
        String tokenA = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-a");
        Started started = start(f, tokenA);

        // 第二个机娘（同一主人名下）拿尾令牌入队。
        Long agentB = insertAgent(f.ownerUserId, "星梦B-" + seq);
        String tokenB = assume(f.ownerAccessToken, agentB, "codex", "run-b");
        MvcResult joinRes = mockMvc.perform(post("/api/v1/agent/collaboration-handoffs/claim")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType("application/json")
                        .content("{\"handoffToken\":\"" + started.handoffToken + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        String ticketB = json(joinRes).get("contributionTicket").get("ticketCode").asText();

        // B 现在只能排队 —— 状态端点如实回答，并给出建议轮询间隔。
        mockMvc.perform(get(ticket(ticketB)).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_PREDECESSOR"))
                .andExpect(jsonPath("$.sequenceNo").value(2))
                .andExpect(jsonPath("$.pollAfterSeconds").value(5));

        // B 此刻想抢跑 → 被闸门拦下，409 且告诉它「前一棒还没完成」。
        mockMvc.perform(post(leases(ticketB)).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACPP_TICKET_WAITING"));

        // A 完成第一棒。
        String leaseA = claimLease(started.ticketCode, tokenA);
        mockMvc.perform(submit(started.ticketCode, tokenA, leaseA, "第一棒正文。"))
                .andExpect(status().isCreated());

        // ★ B 被唤醒了 —— 这一步没有任何人主动通知 B，是 submit 顺手把它放行的。
        mockMvc.perform(get(ticket(ticketB)).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.pollAfterSeconds").value(0));   // 别等了，赶紧领

        // B 写第二棒：只 append，不新建草稿。
        String leaseB = claimLease(ticketB, tokenB);
        mockMvc.perform(submit(ticketB, tokenB, leaseB, "第二棒正文。"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketStatus").value("DONE"));

        Object draftId = sessionColumn(started.postTicket, "draft_id");
        Assertions.assertThat(blockCount(draftId)).as("同一篇草稿里应有两段正文").isEqualTo(2);
        Assertions.assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM draft WHERE post_id = (SELECT post_id FROM collaboration_session "
                                + "WHERE post_ticket = ?)", Integer.class, started.postTicket))
                .as("后续棒不得新建草稿").isEqualTo(1);

        // 两段正文的作者与顺序都对：第 0 块是 A（claude-code），第 1 块是 B（codex）。
        List<Map<String, Object>> blocks = jdbcTemplate.queryForList(
                "SELECT display_order, author_agent_id, source_tool, rendered_content FROM draft_block "
                        + "WHERE draft_id = ? ORDER BY display_order", draftId);
        Assertions.assertThat(blocks.get(0).get("source_tool")).isEqualTo("claude-code");
        Assertions.assertThat(((Number) blocks.get(0).get("author_agent_id")).longValue()).isEqualTo(f.agentId);
        Assertions.assertThat(blocks.get(1).get("display_order")).isEqualTo(1);
        Assertions.assertThat(blocks.get(1).get("source_tool")).isEqualTo("codex");
        Assertions.assertThat(((Number) blocks.get(1).get("author_agent_id")).longValue()).isEqualTo(agentB);
        Assertions.assertThat(blocks.get(1).get("rendered_content")).isEqualTo("第二棒正文。");
    }

    // ══════════════════ 幂等 ══════════════════

    /**
     * ★「submit 幂等」验收：同 key + 同 body 重放 → 返回<b>同一个响应</b>，且业务<b>一行都没再执行</b>。
     *
     * 断言的重点不是"两次都 201"，而是<b>库里只多了一段正文</b>——
     * 幂等的定义是「效果与执行一次完全一致」，效果要去数据里查，不能只看响应。
     */
    @Test
    void replayedSubmitReturnsSameResponseWithoutDuplicating() throws Exception {
        Fixture f = setup("idem");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-idem");
        Started started = start(f, agentToken);
        String leaseToken = claimLease(started.ticketCode, agentToken);

        String key = "idem-key-" + seq;
        MvcResult first = mockMvc.perform(submit(started.ticketCode, agentToken, leaseToken, "只该出现一次。")
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andReturn();

        // 重放：同 key、同 body。
        MvcResult replay = mockMvc.perform(submit(started.ticketCode, agentToken, leaseToken, "只该出现一次。")
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andReturn();

        Assertions.assertThat(replay.getResponse().getContentAsString())
                .as("重放必须返回与首次字节级一致的响应")
                .isEqualTo(first.getResponse().getContentAsString());

        Object draftId = sessionColumn(started.postTicket, "draft_id");
        Assertions.assertThat(blockCount(draftId)).as("业务一行都不该再执行").isEqualTo(1);
        Assertions.assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM contribution WHERE ticket_id = "
                                + "(SELECT id FROM contribution_ticket WHERE ticket_code = ?)",
                        Integer.class, started.ticketCode))
                .isEqualTo(1);

        // 台账落 COMPLETED 且缓存了响应体。
        Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT status, endpoint, response_status, response_body_json FROM idempotency_record "
                        + "WHERE idempotency_key = ?", key);
        Assertions.assertThat(record.get("status")).isEqualTo("COMPLETED");
        Assertions.assertThat(record.get("response_status")).isEqualTo(201);
        // ★ endpoint 存的是【路由模板】而非实际 URI —— 否则同 key 换张票会被切成两个幂等域。
        Assertions.assertThat((String) record.get("endpoint")).contains("{ticketCode}");
    }

    /** 同 key + 不同 body → 409：一个 key 只能对应一件事。 */
    @Test
    void sameKeyDifferentBodyRejected() throws Exception {
        Fixture f = setup("idemdiff");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-idemdiff");
        Started started = start(f, agentToken);
        String leaseToken = claimLease(started.ticketCode, agentToken);

        String key = "idem-diff-" + seq;
        mockMvc.perform(submit(started.ticketCode, agentToken, leaseToken, "原始内容。")
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated());

        mockMvc.perform(submit(started.ticketCode, agentToken, leaseToken, "换了内容。")
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY"));
    }

    /** 没带 Idempotency-Key → 宽松放行（保证 L15 那批既有客户端不会因本课上线而全挂）。 */
    @Test
    void requestWithoutIdempotencyKeyStillWorks() throws Exception {
        Fixture f = setup("nokey");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-nokey");
        Started started = start(f, agentToken);
        String leaseToken = claimLease(started.ticketCode, agentToken);

        mockMvc.perform(submit(started.ticketCode, agentToken, leaseToken, "无 key 也能提交。"))
                .andExpect(status().isCreated());
        Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record", Integer.class)).isEqualTo(0);
    }

    // ══════════════════ 拒绝路径 ══════════════════

    /**
     * ★ 租约过期后提交 → 410，而且<b>没有任何 Worker 参与</b>。
     *
     * 我们只把 lease_expires_at 改到过去，<b>故意不动 status</b>（仍是 ACTIVE）——
     * 模拟「L17 的清理 Worker 还没来标记」。它照样提交不进来，
     * 因为过期判定写在 submit 那条 UPDATE 的 WHERE 里做惰性执行。
     * <b>状态不准 ≠ 行为不对</b>，这正是本课能在「禁止写 Worker」下依然安全的原因。
     */
    @Test
    void submitRejectsExpiredLease() throws Exception {
        Fixture f = setup("expired");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-expired");
        Started started = start(f, agentToken);
        String leaseToken = claimLease(started.ticketCode, agentToken);

        jdbcTemplate.update(
                "UPDATE contribution_attempt SET lease_expires_at = ? WHERE ticket_id = "
                        + "(SELECT id FROM contribution_ticket WHERE ticket_code = ?)",
                Instant.now().minusSeconds(60), started.ticketCode);

        mockMvc.perform(submit(started.ticketCode, agentToken, leaseToken, "来晚了。"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ACPP_LEASE_EXPIRED"));

        // 状态仍是 ACTIVE（没人改过它），但内容一个字都没写进去。
        Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM contribution_attempt WHERE ticket_id = "
                        + "(SELECT id FROM contribution_ticket WHERE ticket_code = ?)",
                String.class, started.ticketCode)).isEqualTo("ACTIVE");
        Assertions.assertThat(sessionColumn(started.postTicket, "draft_id")).isNull();
    }

    /** 别人的席位 → 403 ACPP_WRONG_AGENT（同一主人名下，彼此可见，所以不是 404）。 */
    @Test
    void claimLeaseRejectsWrongAgent() throws Exception {
        Fixture f = setup("wrongagent");
        String tokenA = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-wa");
        Started started = start(f, tokenA);

        Long agentB = insertAgent(f.ownerUserId, "星梦B-" + seq);
        String tokenB = assume(f.ownerAccessToken, agentB, "codex", "run-wb");

        mockMvc.perform(post(leases(started.ticketCode)).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACPP_WRONG_AGENT"));
    }

    /** 已领租约的席位再被领 → 409（同一个机娘重复 claim 也一样，除非带 Idempotency-Key）。 */
    @Test
    void claimLeaseTwiceRejected() throws Exception {
        Fixture f = setup("twice");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-twice");
        Started started = start(f, agentToken);
        claimLease(started.ticketCode, agentToken);

        mockMvc.perform(post(leases(started.ticketCode)).header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACPP_LEASE_ALREADY_CLAIMED"));
    }

    /**
     * 跨主人访问席位 → <b>404 而不是 403</b>。
     * 403 等于承认「它存在、只是不给你」，会泄漏资源存在性、给枚举者提供反馈信号。
     * 对齐 L15 的 handoff 口径与 Pack 多租户授权 §5。
     */
    @Test
    void ticketStatusRejectsCrossTenant() throws Exception {
        Fixture owner1 = setup("tenant1");
        String token1 = assume(owner1.ownerAccessToken, owner1.agentId, "claude-code", "run-t1");
        Started started = start(owner1, token1);

        Fixture owner2 = setup("tenant2");
        String token2 = assume(owner2.ownerAccessToken, owner2.agentId, "codex", "run-t2");

        mockMvc.perform(get(ticket(started.ticketCode)).header("Authorization", "Bearer " + token2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACPP_TICKET_NOT_FOUND"));
    }

    /** 伪造的租约令牌 → 403 LEASE_INVALID。 */
    @Test
    void submitRejectsForgedLeaseToken() throws Exception {
        Fixture f = setup("forged");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-forged");
        Started started = start(f, agentToken);
        claimLease(started.ticketCode, agentToken);

        mockMvc.perform(submit(started.ticketCode, agentToken, "lease_forged_nonexistent", "伪造。"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACPP_LEASE_INVALID"));
    }

    // ══════════════════ 时区探针（从 L15 的 handoff 搬到这里）══════════════════

    /**
     * ★ 时区口径探针（L15 传下来的岗，DRIFT D-15 → D-16 迁移到 lease）。
     *
     * Java 写进去的 lease_expires_at，用<b>数据库自己的时钟</b>看也该是「约 15 分钟后」。
     * 由来：L15 的令牌消费第一版把时效比较写成 {@code expires_at >= NOW(3)}，被测试打红——
     * 因为 expires_at 是 Java 写的、NOW(3) 是数据库读的，两侧口径由 JDBC 的 serverTimezone 决定，
     * 主库 URL 带 UTC 而 Testcontainers 自建 URL 没带，实测差 8 小时。
     *
     * <p>消费路径已统一改用应用时钟 {@code #{now}} 规避，但这个口径差本身仍要盯住——
     * <b>L17 的清理 Worker 用的正是 {@code WHERE lease_expires_at < NOW(3)}</b>，
     * 口径一偏，它要么提前清掉有效租约、要么永远清不掉超时的。这条测试就是给 L17 提前埋的守卫。
     */
    @Test
    void leaseExpiryAgreesWithDatabaseClock() throws Exception {
        Fixture f = setup("tz");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-tz");
        Started started = start(f, agentToken);
        claimLease(started.ticketCode, agentToken);

        Integer minutesFromDbClock = jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(MINUTE, NOW(3), a.lease_expires_at) FROM contribution_attempt a "
                        + "JOIN contribution_ticket t ON t.id = a.ticket_id WHERE t.ticket_code = ?",
                Integer.class, started.ticketCode);
        Assertions.assertThat(minutesFromDbClock)
                .as("Java 写入的 lease_expires_at 与数据库 NOW(3) 的口径必须一致（差值≈TTL 15min）；"
                        + "若差出整数个小时，说明 JDBC 时区参数没对齐——L17 的清理 Worker 会因此扫错")
                .isBetween(14, 15);
    }

    // —— 辅助 ——

    private String leases(String ticketCode) {
        return "/api/v1/agent/contribution-tickets/" + ticketCode + "/leases";
    }

    private String ticket(String ticketCode) {
        return "/api/v1/agent/contribution-tickets/" + ticketCode;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(
            String ticketCode, String agentToken, String leaseToken, String content) {
        return post("/api/v1/agent/contribution-tickets/" + ticketCode + "/contributions")
                .header("Authorization", "Bearer " + agentToken)
                .header("X-Turn-Lease-Token", leaseToken)
                .contentType("application/json")
                .content("{\"content\":\"" + content + "\"}");
    }

    private String claimLease(String ticketCode, String agentToken) throws Exception {
        MvcResult res = mockMvc.perform(post(leases(ticketCode))
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isCreated()).andReturn();
        return json(res).get("leaseToken").asText();
    }

    private Started start(Fixture f, String agentToken) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/agent/collaboration-sessions")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"L16 验收\",\"channelId\":" + f.channelId + "}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json(res);
        return new Started(body.get("postTicket").asText(),
                body.get("contributionTicket").get("ticketCode").asText(),
                body.get("nextHandoffToken").asText());
    }

    private JsonNode json(MvcResult res) throws Exception {
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private String ticketStatusInDb(String ticketCode) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM contribution_ticket WHERE ticket_code = ?", String.class, ticketCode);
    }

    private Object sessionColumn(String postTicket, String column) {
        return jdbcTemplate.queryForMap(
                "SELECT " + column + " FROM collaboration_session WHERE post_ticket = ?", postTicket)
                .get(column);
    }

    private Integer blockCount(Object draftId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM draft_block WHERE draft_id = ?", Integer.class, draftId);
    }

    private Fixture setup(String tag) throws Exception {
        String suffix = tag + (++seq);
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO forum_channel(slug,name,sort_order,enabled,post_count,created_at,updated_at) "
                        + "VALUES (?,?,0,TRUE,0,?,?)", "ch-" + suffix, "开发日志", now, now);
        Long channelId = jdbcTemplate.queryForObject(
                "SELECT id FROM forum_channel ORDER BY id DESC LIMIT 1", Long.class);

        String email = "lease-" + suffix + "@example.com";
        MvcResult pairResult = mockMvc.perform(post("/api/v1/cli/device-pairings")
                        .contentType("application/json")
                        .content("{\"installationCode\":\"inst-" + suffix + "\",\"deviceName\":\"dev\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode pair = json(pairResult);

        MockHttpSession session = AuthTestSupport.insertUserAndLogin(
                mockMvc, jdbcTemplate, passwordEncoder, "lease-" + suffix, email);
        mockMvc.perform(post("/api/v1/web/device-pairings/confirm").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"userCode\":\"" + pair.get("userCode").asText() + "\"}"))
                .andExpect(status().isNoContent());

        MvcResult tokenResult = mockMvc.perform(post("/api/v1/cli/device-pairings/token")
                        .contentType("application/json")
                        .content("{\"deviceCode\":\"" + pair.get("deviceCode").asText() + "\"}"))
                .andExpect(jsonPath("$.status").value("APPROVED")).andReturn();
        String ownerAccessToken = json(tokenResult).get("ownerAccessToken").asText();

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
        return json(result).get("agentActingToken").asText();
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

    private record Started(String postTicket, String ticketCode, String handoffToken) {
    }
}
