package com.agentlog.reliability;

import com.agentlog.collaboration.application.ClaimHandoffService;
import com.agentlog.collaboration.application.ClaimLeaseService;
import com.agentlog.collaboration.application.StartCollaborationService;
import com.agentlog.collaboration.application.SubmitContributionService;
import com.agentlog.collaboration.api.dto.request.ClaimHandoffRequest;
import com.agentlog.collaboration.api.dto.request.StartCollaborationRequest;
import com.agentlog.collaboration.api.dto.request.SubmitContributionRequest;
import com.agentlog.collaboration.api.dto.response.ClaimLeaseResponse;
import com.agentlog.reliability.worker.ExpiredAttemptWorker;
import com.agentlog.reliability.infrastructure.persistence.dataobject.ErrorReportDO;
import com.agentlog.reliability.infrastructure.persistence.mapper.ErrorReportMapper;
import com.agentlog.audit.infrastructure.persistence.mapper.AuditRecordMapper;
import com.agentlog.shared.security.AgentIdentity;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L17 Worker 集成测试：
 * 验收①「超时阻塞后序」· 验收②「多 Worker 不重复」· 时区探针 · 毒丸隔离 · submit/Worker 竞争
 *
 * <p>⚠️ 本类不加 @Transactional：Worker 和测试业务必须用各自独立的事务，
 * 才能真正测出并发语义（同 LeaseConcurrencyIntegrationTest 的理由）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class WorkerIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.0").withUrlParam("serverTimezone", "UTC");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.data.redis.host", redis::getHost);
        reg.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        reg.add("agentlog.token.pepper", () -> "test-pepper");
    }

    @Autowired StartCollaborationService startService;
    @Autowired ClaimHandoffService claimHandoffService;
    @Autowired ClaimLeaseService claimLeaseService;
    @Autowired SubmitContributionService submitService;
    @Autowired ExpiredAttemptWorker worker;
    @Autowired com.agentlog.collaboration.CollaborationFacade collaborationFacade;
    @Autowired ErrorReportMapper errorReportMapper;
    @Autowired AuditRecordMapper auditRecordMapper;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    // ── 内部 Stub / Fixture ──────────────────────────────────────────────────

    private record StubAgent(Long agentAccountId, Long ownerUserId,
                             Long installationId, String sourceTool,
                             String clientRunId) implements AgentIdentity {}

    private record Fixture(Long ownerUserId, Long channelId, Long agentId, Long agent2Id) {}

    private Fixture fixture() {
        int n = SEQ.incrementAndGet();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO user_account(username,email,password_hash,status,created_at,updated_at) "
                + "VALUES (?,?,'{noop}x','ACTIVE',?,?)",
                "w-" + n, "w" + n + "@example.com", now, now);
        Long owner = jdbc.queryForObject("SELECT id FROM user_account WHERE email=?",
                Long.class, "w" + n + "@example.com");

        jdbc.update("INSERT INTO forum_channel(slug,name,sort_order,enabled,post_count,created_at,updated_at) "
                + "VALUES (?,?,0,TRUE,0,?,?)", "w-ch-" + n, "Worker测试", now, now);
        Long ch = jdbc.queryForObject("SELECT id FROM forum_channel WHERE slug=?",
                Long.class, "w-ch-" + n);

        jdbc.update("INSERT INTO agent_account(owner_user_id,nickname,status,created_at,updated_at) "
                + "VALUES (?,'w-agent-A-" + n + "','ACTIVE',?,?)", owner, now, now);
        Long agA = jdbc.queryForObject("SELECT id FROM agent_account WHERE nickname=?",
                Long.class, "w-agent-A-" + n);

        jdbc.update("INSERT INTO agent_account(owner_user_id,nickname,status,created_at,updated_at) "
                + "VALUES (?,'w-agent-B-" + n + "','ACTIVE',?,?)", owner, now, now);
        Long agB = jdbc.queryForObject("SELECT id FROM agent_account WHERE nickname=?",
                Long.class, "w-agent-B-" + n);

        return new Fixture(owner, ch, agA, agB);
    }

    private AgentIdentity agent(Long agentId, Long ownerId) {
        return new StubAgent(agentId, ownerId, 1L, "codex", "run-" + agentId);
    }

    private StartCollaborationRequest startReq(String title, Long channelId) {
        return new StartCollaborationRequest(title, channelId, null, null);
    }

    // ── 1. 时区探针 ──────────────────────────────────────────────────────────
    @Test
    void workerPicksUpAttemptExpiredJustOneSecondAgo() {
        Fixture f = fixture();
        AgentIdentity ag = agent(f.agentId(), f.ownerUserId());
        // ★ start 返回的就是【首棒票】，它天然是 READY_TO_WRITE，可直接领租约。
        //   若再 join 一次，拿到的是第 2 棒——前序未完成 → WAITING_PREDECESSOR，领不了租约。
        var started = startService.start(ag, startReq("时区探针", f.channelId()));
        String ticketCode = started.contributionTicket().ticketCode();
        claimLeaseService.claim(ag, ticketCode);
        forceExpireAttempt(ticketCode);
        worker.sweepOnce();
        assertAttemptStatus(ticketCode, "FAILED_TIMEOUT");
    }

    // ── 2. 中间棒超时 → 后序被阻塞 + 会话暂停（硬验收①） ──────────────────
    //
    // ★ 必须让首棒【真正提交成功】，第二棒才算"中间棒"。
    //   若直接让首棒超时，按设计走的是另一条分支：session → INVALIDATED（见测试 3）——
    //   因为首棒失败意味着 post/draft 从未创建，整局没有任何东西可救。
    //   「中间棒失败」才是"保留草稿 + 阻塞后序 + 等主人 retry"的场景。
    @Test
    void timeoutBlocksSuccessors() {
        Fixture f = fixture();
        AgentIdentity agA = agent(f.agentId(),  f.ownerUserId());
        AgentIdentity agB = agent(f.agent2Id(), f.ownerUserId());

        // 首棒：领租约 → 正常提交 → DONE，草稿建出来
        var started    = startService.start(agA, startReq("后序阻塞", f.channelId()));
        String ticket1 = started.contributionTicket().ticketCode();
        var lease1     = claimLeaseService.claim(agA, ticket1);
        submitService.submit(agA, ticket1, lease1.leaseToken(),
                new SubmitContributionRequest("第一棒的内容", null));
        assertTicketStatus(ticket1, "DONE");

        // 第二棒（中间棒）：agentB 入队，前序已 DONE → READY_TO_WRITE
        var join2      = claimHandoffService.claim(agB, new ClaimHandoffRequest(started.nextHandoffToken()));
        String ticket2 = join2.contributionTicket().ticketCode();
        // 第三棒：agentA 再入队，排在 ticket2 之后 → WAITING_PREDECESSOR
        var join3      = claimHandoffService.claim(agA, new ClaimHandoffRequest(join2.nextHandoffToken()));
        String ticket3 = join3.contributionTicket().ticketCode();
        assertTicketStatus(ticket3, "WAITING_PREDECESSOR");

        // 中间棒领了租约却超时
        claimLeaseService.claim(agB, ticket2);
        forceExpireAttempt(ticket2);
        worker.sweepOnce();

        assertTicketStatus(ticket2, "FAILED_TIMEOUT");
        assertTicketStatus(ticket3, "BLOCKED_BY_PREDECESSOR");   // ★ 后序被阻塞
        assertSessionStatus(started.postTicket(), "PAUSED_ON_ERROR");

        // ⚠️ error_report 由 @ApplicationModuleListener 【异步】写入（L17 重构后），要等一下。
        //    状态推进是同步的（上面三条），派生行为是异步的——这正是「不变量靠状态机、
        //    事件只做派生行为」这条边界在测试里的样子。
        await(5000, () -> !errorReportMapper.selectList(null).isEmpty());
        List<ErrorReportDO> reports = errorReportMapper.selectList(null);
        assertThat(reports).anyMatch(r -> "LEASE_TIMEOUT".equals(r.getErrorType()));
    }

    // ── 2b. ★ 阻塞必须传播到【整条尾巴】，不只是直接后继 ──────────────────
    //
    // ★ 这条测试是主人 2026-08-14 读代码陪读时发现的 bug 补的：
    //   原来的 blockSuccessors 只匹配 predecessor_ticket_id = 超时票，
    //   也就是【只冻结直接后继一张】。第 4、5、6 棒的 predecessor 指向第 3 棒，匹配不上。
    //   而上面那条测试只建了 3 棒（第 3 棒恰好就是直接后继），所以【测绿了但没测到】。
    //   ——「后序 N 张」这个说法在测试里从没被真正验证过。
    @Test
    void timeoutBlocksEntireTail() {
        Fixture f = fixture();
        AgentIdentity agA = agent(f.agentId(),  f.ownerUserId());
        AgentIdentity agB = agent(f.agent2Id(), f.ownerUserId());

        // 首棒正常完成
        var started    = startService.start(agA, startReq("整条尾巴阻塞", f.channelId()));
        String ticket1 = started.contributionTicket().ticketCode();
        var lease1     = claimLeaseService.claim(agA, ticket1);
        submitService.submit(agA, ticket1, lease1.leaseToken(),
                new SubmitContributionRequest("第一棒", null));

        // 排出 4 棒的队：#2(中间棒·会死) → #3 → #4
        var join2 = claimHandoffService.claim(agB, new ClaimHandoffRequest(started.nextHandoffToken()));
        String ticket2 = join2.contributionTicket().ticketCode();
        var join3 = claimHandoffService.claim(agA, new ClaimHandoffRequest(join2.nextHandoffToken()));
        String ticket3 = join3.contributionTicket().ticketCode();
        var join4 = claimHandoffService.claim(agB, new ClaimHandoffRequest(join3.nextHandoffToken()));
        String ticket4 = join4.contributionTicket().ticketCode();

        assertTicketStatus(ticket3, "WAITING_PREDECESSOR");
        assertTicketStatus(ticket4, "WAITING_PREDECESSOR");

        // 第 2 棒超时
        claimLeaseService.claim(agB, ticket2);
        forceExpireAttempt(ticket2);
        worker.sweepOnce();

        assertTicketStatus(ticket2, "FAILED_TIMEOUT");
        assertTicketStatus(ticket3, "BLOCKED_BY_PREDECESSOR");   // 直接后继
        // ★★ 关键断言：隔了一层的 #4 也必须被阻塞。
        //    第 2 棒的内容缺失，第 4 棒同样会基于残缺文章续写——阻塞理由对整条尾巴都成立。
        assertTicketStatus(ticket4, "BLOCKED_BY_PREDECESSOR");
    }

    // ── 3. 首棒超时 → INVALIDATED + 无草稿 ─────────────────────────────────
    @Test
    void firstTurnTimeoutInvalidatesSessionAndLeavesNoDraft() {
        Fixture f = fixture();
        AgentIdentity ag = agent(f.agentId(), f.ownerUserId());
        var started = startService.start(ag, startReq("首棒超时", f.channelId()));
        String ticketCode = started.contributionTicket().ticketCode();
        claimLeaseService.claim(ag, ticketCode);
        forceExpireAttempt(ticketCode);
        worker.sweepOnce();

        assertSessionStatus(started.postTicket(), "INVALIDATED");
        Long draftCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM collaboration_session WHERE post_ticket=? AND draft_id IS NOT NULL",
                Long.class, started.postTicket());
        assertThat(draftCount).isZero();
    }

    // ── 4. 两个 Worker 并发扫，不重复处理（硬验收②） ──────────────────────
    @Test
    void twoWorkersDoNotDuplicateProcessing() throws InterruptedException {
        Fixture f = fixture();
        AgentIdentity ag = agent(f.agentId(), f.ownerUserId());
        var started = startService.start(ag, startReq("多Worker", f.channelId()));
        String ticketCode = started.contributionTicket().ticketCode();
        claimLeaseService.claim(ag, ticketCode);
        forceExpireAttempt(ticketCode);

        // ★ 两个线程同时 sweep，模拟两个实例。
        //   防线有两道：① lockExpiredAttempts 的 FOR UPDATE SKIP LOCKED（拿不到就跳过）
        //              ② expireAttempt 的闸门（拿到了也可能已被处理，affectedRows=0 直接返回）
        AtomicInteger declared = new AtomicInteger();
        Runnable sweepAndCount = () -> { if (sweepDeclaredOne(ticketCode)) declared.incrementAndGet(); };
        Thread t1 = new Thread(sweepAndCount);
        Thread t2 = new Thread(sweepAndCount);
        t1.start(); t2.start(); t1.join(); t2.join();

        // ★ 同步证据：恰好一个线程真正拿到了「宣告权」（闸门只放行一个）。
        //   不依赖异步的 error_report —— 那是派生行为，晚一点才落库。
        assertThat(declared.get())
                .as("两个 Worker 并发扫描，只能有一个真正宣告这条 attempt 超时")
                .isEqualTo(1);

        // 异步证据：事故报告也只有一条（等发件箱投递完成）
        await(5000, () -> countErrorReports(ticketCode) > 0);
        assertThat(countErrorReports(ticketCode))
                .as("事故报告不能重复写")
                .isEqualTo(1L);
    }

    /** 直接调 Facade 走一轮「拿活 + 宣告」，返回本次是否真的由我宣告成功。 */
    private boolean sweepDeclaredOne(String ticketCode) {
        Long attemptId = jdbc.queryForObject(
                "SELECT ca.id FROM contribution_attempt ca JOIN contribution_ticket ct ON ca.ticket_id=ct.id WHERE ct.ticket_code=?",
                Long.class, ticketCode);
        return collaborationFacade.expireAttempt(attemptId);
    }

    private Long countErrorReports(String ticketCode) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM error_report er JOIN contribution_ticket ct ON er.ticket_id=ct.id WHERE ct.ticket_code=?",
                Long.class, ticketCode);
    }

    // ── 5. submit 与 Worker 竞争：Worker 先行则 submit 拿 LEASE_EXPIRED ───
    @Test
    void workerWinsRaceAgainstSubmit() {
        Fixture f = fixture();
        AgentIdentity ag = agent(f.agentId(), f.ownerUserId());
        var started = startService.start(ag, startReq("竞争测试", f.channelId()));
        String ticketCode = started.contributionTicket().ticketCode();
        ClaimLeaseResponse lease = claimLeaseService.claim(ag, ticketCode);
        forceExpireAttempt(ticketCode);
        worker.sweepOnce();

        boolean gotExpired = false;
        try {
            submitService.submit(ag, ticketCode, lease.leaseToken(),
                    new SubmitContributionRequest("竞争内容", null));
        } catch (com.agentlog.shared.error.ApiException e) {
            gotExpired = (e.status() == HttpStatus.GONE
                    && com.agentlog.shared.error.ApiStatus.ACPP_LEASE_EXPIRED.getCode().equals(e.code()));
        }
        assertThat(gotExpired).as("Worker 先行后 submit 必须拿 LEASE_EXPIRED(410)").isTrue();
    }

    // ── 6. 审计记录写入 ───────────────────────────────────────────────────
    @Test
    void auditRecordWrittenAfterWorkerSweep() {
        Fixture f = fixture();
        AgentIdentity ag = agent(f.agentId(), f.ownerUserId());
        var started = startService.start(ag, startReq("审计测试", f.channelId()));
        String ticketCode = started.contributionTicket().ticketCode();
        claimLeaseService.claim(ag, ticketCode);
        forceExpireAttempt(ticketCode);
        worker.sweepOnce();
        await(5000, () -> auditRecordMapper.selectCount(null) > 0);
        assertThat(auditRecordMapper.selectCount(null)).isGreaterThan(0);
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    private void forceExpireAttempt(String ticketCode) {
        jdbc.update(
                "UPDATE contribution_attempt ca JOIN contribution_ticket ct ON ca.ticket_id=ct.id " +
                "SET ca.lease_expires_at=? WHERE ct.ticket_code=?",
                Instant.now().minus(Duration.ofSeconds(1)), ticketCode);
    }

    private void assertAttemptStatus(String ticketCode, String expected) {
        String s = jdbc.queryForObject(
                "SELECT ca.status FROM contribution_attempt ca JOIN contribution_ticket ct ON ca.ticket_id=ct.id WHERE ct.ticket_code=?",
                String.class, ticketCode);
        assertThat(s).as("attempt of %s", ticketCode).isEqualTo(expected);
    }

    private void assertTicketStatus(String ticketCode, String expected) {
        String s = jdbc.queryForObject(
                "SELECT status FROM contribution_ticket WHERE ticket_code=?", String.class, ticketCode);
        assertThat(s).as("ticket %s", ticketCode).isEqualTo(expected);
    }

    private void assertSessionStatus(String postTicket, String expected) {
        String s = jdbc.queryForObject(
                "SELECT status FROM collaboration_session WHERE post_ticket=?", String.class, postTicket);
        assertThat(s).as("session %s", postTicket).isEqualTo(expected);
    }

    private void await(long maxMs, java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
        }
    }
}
