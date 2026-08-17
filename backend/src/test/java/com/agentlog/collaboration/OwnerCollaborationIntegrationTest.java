package com.agentlog.collaboration;

import com.agentlog.collaboration.api.dto.request.ClaimHandoffRequest;
import com.agentlog.collaboration.api.dto.request.StartCollaborationRequest;
import com.agentlog.collaboration.api.dto.request.SubmitContributionRequest;
import com.agentlog.collaboration.api.dto.response.CollaborationDetailView;
import com.agentlog.collaboration.application.ClaimHandoffService;
import com.agentlog.collaboration.application.ClaimLeaseService;
import com.agentlog.collaboration.application.CollaborationTimelineService;
import com.agentlog.collaboration.application.ReissueHandoffService;
import com.agentlog.collaboration.application.ReportClientFailureService;
import com.agentlog.collaboration.application.RetryTicketService;
import com.agentlog.collaboration.application.StartCollaborationService;
import com.agentlog.collaboration.application.StopCollaborationService;
import com.agentlog.collaboration.application.SubmitContributionService;
import com.agentlog.reliability.worker.ExpiredAttemptWorker;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.security.AgentIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L18 主人决策链集成测试：retry / 结束协作 / 重新签发尾令牌 / 时间线 / 自报失败。
 *
 * <p>验收栏两条：<b>「错误历史保留」</b>（{@link #retryKeepsFailedAttemptAsHistory}）与
 * <b>「原机娘恢复」</b>（{@link #onlyOriginalAgentCanWriteAfterRetry}）。
 *
 * <p>⚠️ 本类<b>不加 {@code @Transactional}</b>：并发测试要求各线程用各自独立的事务，
 * 否则它们共享同一条连接、形成不了竞争，测试会绿得毫无意义（L15/L16/L17 都写了这条）。
 * <p>⚠️ MySQL 容器<b>必须</b> {@code withUrlParam("serverTimezone","UTC")}——
 * 不加则与主应用差 8 小时，而 Worker 判错是<b>静默</b>的（只少捞/多捞几条，测试不会红）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OwnerCollaborationIntegrationTest {

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
    @Autowired RetryTicketService retryService;
    @Autowired StopCollaborationService stopService;
    @Autowired ReissueHandoffService reissueService;
    @Autowired CollaborationTimelineService timelineService;
    @Autowired ReportClientFailureService reportFailureService;
    @Autowired ExpiredAttemptWorker worker;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private record StubAgent(Long agentAccountId, Long ownerUserId, Long installationId,
                             String sourceTool, String clientRunId) implements AgentIdentity {}

    private record Fixture(Long ownerUserId, Long channelId, Long agentId, Long agent2Id) {}

    // ═══════════════ ① retry：解冻整条尾巴（皇冠测试）═══════════════

    /**
     * ★★ 本课的皇冠测试：解冻必须走到<b>整条尾巴</b>，不是只解冻直接后继一张 ★★
     *
     * <p><b>为什么必须排 4 棒</b>：3 棒时「直接后继」与「整条尾巴」<b>不可区分</b>——
     * 第 3 棒恰好就是第 2 棒的直接后继，写错的实现也能测绿。
     * L17 的 {@code blockSuccessors} bug 正是这么漏掉的（主人读代码时才发现）。
     * <p>★ 判据：<b>测试数据的形状决定了它能发现什么。</b>链表的传递性要 4 个节点，竞态要 8 线程。
     *
     * <p>同时验证一个<b>与 L17 不对称</b>的地方：解冻的目标是 {@code WAITING_PREDECESSOR}
     * 而不是 {@code READY_TO_WRITE}——第 2 棒只是"可以重写了"，还没写完，
     * 第 3 棒此刻仍然没有资格落笔。
     */
    @Test
    void retryUnblocksEntireTailBackToWaiting() {
        Fixture f = fixture();
        AgentIdentity agA = agent(f.agentId(), f.ownerUserId());
        AgentIdentity agB = agent(f.agent2Id(), f.ownerUserId());

        // 首棒正常完成，草稿建出来
        var started = startService.start(agA, startReq("解冻整条尾巴", f.channelId()));
        String t1 = started.contributionTicket().ticketCode();
        var lease1 = claimLeaseService.claim(agA, t1);
        submitService.submit(agA, t1, lease1.leaseToken(),
                new SubmitContributionRequest("第一棒", null));

        // 排出 4 棒：#2(会死) → #3 → #4
        var join2 = claimHandoffService.claim(agB, new ClaimHandoffRequest(started.nextHandoffToken()));
        String t2 = join2.contributionTicket().ticketCode();
        var join3 = claimHandoffService.claim(agA, new ClaimHandoffRequest(join2.nextHandoffToken()));
        String t3 = join3.contributionTicket().ticketCode();
        var join4 = claimHandoffService.claim(agB, new ClaimHandoffRequest(join3.nextHandoffToken()));
        String t4 = join4.contributionTicket().ticketCode();

        // 第 2 棒超时 → L17 把整条尾巴冻住
        claimLeaseService.claim(agB, t2);
        forceExpireAttempt(t2);
        worker.sweepOnce();
        assertTicketStatus(t2, "FAILED_TIMEOUT");
        assertTicketStatus(t3, "BLOCKED_BY_PREDECESSOR");
        assertTicketStatus(t4, "BLOCKED_BY_PREDECESSOR");   // 隔了一层也被冻住（L17 修的 bug）
        assertSessionStatus(started.postTicket(), "PAUSED_ON_ERROR");

        // ★ 主人 retry
        retryService.retry(f.ownerUserId(), started.postTicket(), t2);

        assertTicketStatus(t2, "READY_TO_WRITE");           // 死的那张回到可写
        assertTicketStatus(t3, "WAITING_PREDECESSOR");      // 直接后继回到排队
        assertTicketStatus(t4, "WAITING_PREDECESSOR");      // ★ 隔了一层的第 4 棒【也】解冻了
        assertHandoffStatusOfTail(started.postTicket(), "AVAILABLE");  // 尾令牌解冻
    }

    /**
     * retry 之后会话落 {@code AWAITING_CONTINUATION}，<b>不是 RUNNING</b>。
     *
     * <p>蓝图状态机画的是 {@code PAUSED_ON_ERROR --> RUNNING: retry}，但那与 L16 自己定的判据冲突：
     * <b>状态要如实反映「有没有一棒正在进行」</b>。retry 那一刻没有任何人在写——
     * 原机娘还得自己回来 claim-turn。
     */
    @Test
    void retryPutsSessionBackToAwaitingNotRunning() {
        Scenario s = scenarioWithTimedOutMiddleTurn("retry后会话状态");
        retryService.retry(s.ownerUserId(), s.postTicket(), s.deadTicket());
        assertSessionStatus(s.postTicket(), "AWAITING_CONTINUATION");
    }

    /**
     * ★ 验收①「<b>错误历史保留</b>」：retry 不碰失败的那条 attempt，新的另起一行。
     *
     * <p>attempt 是冻结面里<b>唯一只进不出</b>的——它记的是「发生过什么」，而历史不能改。
     * 同 {@code audit_record}/{@code error_report} 没有 {@code updated_at} 是一个道理。
     */
    @Test
    void retryKeepsFailedAttemptAsHistory() {
        Scenario s = scenarioWithTimedOutMiddleTurn("错误历史保留");

        retryService.retry(s.ownerUserId(), s.postTicket(), s.deadTicket());
        // 原机娘换个新对话回来，重新领租约 → 长出 attempt_no=2
        claimLeaseService.claim(s.deadTicketAgent(), s.deadTicket());

        List<java.util.Map<String, Object>> attempts = jdbc.queryForList(
                "SELECT ca.attempt_no, ca.status FROM contribution_attempt ca "
                        + "JOIN contribution_ticket ct ON ct.id=ca.ticket_id "
                        + "WHERE ct.ticket_code=? ORDER BY ca.attempt_no", s.deadTicket());

        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0)).containsEntry("attempt_no", 1)
                .containsEntry("status", "FAILED_TIMEOUT");   // ★ 第一次的失败原样留着
        assertThat(attempts.get(1)).containsEntry("attempt_no", 2)
                .containsEntry("status", "ACTIVE");           // 新的一次
    }

    /**
     * ★ 验收②「<b>原机娘恢复</b>」：retry 之后只有原机娘能继续写，换一只会被拒。
     *
     * <p>这条规则是 {@code APPROVAL_RECORD.md:28} 冻结的（「retry 只允许原机娘，但允许换新对话」），
     * 而实现它<b>不需要任何新代码</b>——票的 {@code required_agent_id} 是 join 时由
     * 「谁消费了那张令牌」决定的，retry 不动它，claim lease 的闸门自然会拦下别人。
     * <p>★ 本测试的意义正在于此：<b>证明一条不需要写代码的规则真的成立</b>。
     */
    @Test
    void onlyOriginalAgentCanWriteAfterRetry() {
        Scenario s = scenarioWithTimedOutMiddleTurn("原机娘恢复");
        retryService.retry(s.ownerUserId(), s.postTicket(), s.deadTicket());

        // 换一只机娘来领 → 403 ACPP_WRONG_AGENT
        assertThatThrownBy(() -> claimLeaseService.claim(s.otherAgent(), s.deadTicket()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("另一个机娘");

        // 原机娘（换了新对话，clientRunId 不同）→ 放行
        AgentIdentity sameAgentNewConversation = new StubAgent(
                s.deadTicketAgent().agentAccountId(), s.ownerUserId(), 1L, "codex", "run-brand-new");
        assertThat(claimLeaseService.claim(sameAgentNewConversation, s.deadTicket()).leaseToken())
                .isNotBlank();
    }

    // ═══════════════ ② retry 的闸门 ═══════════════

    /** 连点两次 retry：第二次被闸门挡下。★ 闸门本身就是幂等，不需要 {@code @Idempotent}。 */
    @Test
    void retryTwiceIsRejectedByGate() {
        Scenario s = scenarioWithTimedOutMiddleTurn("连点两次");
        retryService.retry(s.ownerUserId(), s.postTicket(), s.deadTicket());

        assertThatThrownBy(() -> retryService.retry(s.ownerUserId(), s.postTicket(), s.deadTicket()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("只有超时失败的席位可以重试");
    }

    /**
     * 不能「跳过死掉的那一棒」去 retry 后面那张被阻塞的票。
     *
     * <p>把 BLOCKED 的票改成可写毫无意义——它的前序仍然是死的，它等的信号永远不会来。
     * 想真的跳过死棒是<b>另一个动作</b>（改因果链指针），已登记 DRIFT 遗留。
     */
    @Test
    void cannotRetryBlockedTicketToSkipTheDeadOne() {
        Scenario s = scenarioWithTimedOutMiddleTurn("不能跳过死棒");
        assertThatThrownBy(() -> retryService.retry(s.ownerUserId(), s.postTicket(), s.blockedTicket()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("只有超时失败的席位可以重试");
    }

    /**
     * ★ 跨闸门竞态：协作已被结束之后，retry 必须失败。
     *
     * <p>retry 的闸门在<b>票</b>上、stop 的闸门在<b>会话</b>上，<b>两把锁管的是不同的行、互相拦不住</b>。
     * 所以 retry 的那条 UPDATE 必须把 {@code cs.status='PAUSED_ON_ERROR'} 纳入自己的 WHERE。
     * <p>★ 判据：<b>当两个操作的闸门落在不同对象上时，必须有一方把对方的条件纳入自己的 WHERE</b>，
     * 否则两把锁各自都"成功"，合起来却破坏了不变量（协作已收工，却有一张票被改回了可写）。
     */
    @Test
    void cannotRetryAfterCollaborationStopped() {
        Scenario s = scenarioWithTimedOutMiddleTurn("停了就不能retry");
        stopService.stop(s.ownerUserId(), s.postTicket());

        assertThatThrownBy(() -> retryService.retry(s.ownerUserId(), s.postTicket(), s.deadTicket()))
                .isInstanceOf(ApiException.class);
    }

    /** 跨主人一律 404（不是 403）——403 等于承认「它存在、只是不给你」，会泄漏资源存在性。 */
    @Test
    void anotherOwnerGetsNotFoundNotForbidden() {
        Scenario s = scenarioWithTimedOutMiddleTurn("跨主人404");
        Fixture other = fixture();
        assertThatThrownBy(() -> retryService.retry(other.ownerUserId(), s.postTicket(), s.deadTicket()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("协作不存在");
    }

    // ═══════════════ ③ 结束协作 ═══════════════

    /** 结束协作四步：会话交审稿、未完成席位取消、尾令牌吊销；<b>已完成的票不动</b>。 */
    @Test
    void stopCancelsUnfinishedTicketsAndRevokesTailToken() {
        Scenario s = scenarioWithTimedOutMiddleTurn("结束协作");

        var outcome = stopService.stop(s.ownerUserId(), s.postTicket());

        assertSessionStatus(s.postTicket(), "READY_FOR_OWNER_REVIEW");
        assertTicketStatus(s.doneTicket(), "DONE");             // ★ 历史不动
        assertTicketStatus(s.deadTicket(), "FAILED_TIMEOUT");   // ★ 历史不动
        assertTicketStatus(s.blockedTicket(), "CANCELLED");     // 未完成的被取消
        assertHandoffStatusOfTail(s.postTicket(), "REVOKED");
        assertThat(outcome.cancelledTicketCount()).isEqualTo(1);
    }

    /**
     * ★ CANCELLED 存在的<b>全部意义</b>：让还在轮询的机娘立刻停下来。
     *
     * <p>不加这个状态值的话，第 3 棒的机娘跑着 {@code collab wait} 查到票仍是
     * {@code WAITING_PREDECESSOR}，服务端答「5 秒后再来问」——它会等一个永远不来的信号，
     * 直到 900 秒超时退出。那句话是真的，但真相是「协作已经收工了」。
     */
    @Test
    void cancelledTicketTellsAgentToStopPolling() {
        Scenario s = scenarioWithTimedOutMiddleTurn("别再轮询了");
        stopService.stop(s.ownerUserId(), s.postTicket());

        var view = jdbc.queryForObject(
                "SELECT status FROM contribution_ticket WHERE ticket_code=?", String.class, s.blockedTicket());
        assertThat(view).isEqualTo("CANCELLED");
        // pollAfterSeconds 为 0 = 别等了。CANCELLED 不在「值得再问」的名单里。
        assertThat(com.agentlog.collaboration.domain.TicketStatus.WAITING_PREDECESSOR.getCode())
                .isNotEqualTo(view);
    }

    /**
     * ★★ 竞态皇冠测试：8 个线程同时点「结束协作」，只有 1 个能成 ★★
     *
     * <p><b>主断言是同步且确定的</b>——数 {@code stop()} 成功的次数，而不是去数异步产物
     * （L17 的教训：断言 {@code error_report} 条数测的其实是"发件箱投递了几次"，还会偶发红）。
     * <p><b>为什么要 8 个线程</b>：2 个可能侥幸错开，测不出竞态（L15/L16 的教训）。
     */
    @Test
    void concurrentStopOnlyOneWins() throws Exception {
        Scenario s = scenarioWithTimedOutMiddleTurn("八线程结束协作");

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    fire.await();
                    stopService.stop(s.ownerUserId(), s.postTicket());
                    succeeded.incrementAndGet();
                } catch (ApiException e) {
                    rejected.incrementAndGet();
                } catch (Exception ignored) {
                    // 其余异常不计入任一侧，下面的断言会因总数对不上而失败
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        fire.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(succeeded.get()).as("只有一个线程真的结束了协作").isEqualTo(1);
        assertThat(rejected.get()).as("其余全部被闸门干净地拒绝").isEqualTo(threads - 1);
        assertSessionStatus(s.postTicket(), "READY_FOR_OWNER_REVIEW");
    }

    // ═══════════════ ④ 重新签发尾令牌 ═══════════════

    /**
     * 重签：旧令牌被吊销、新明文只出现一次、链尾指针换新。
     *
     * <p>★ 闸门落在<b>旧令牌</b>上——因为重签前后 session 与链尾票的状态<b>都不变</b>，
     * 它们记不住"重签发生过"；而旧令牌 {@code AVAILABLE/FROZEN → REVOKED} 这个变化
     * <b>本身就是那条记录</b>。
     */
    @Test
    void reissueRevokesOldTokenAndIssuesNewOne() {
        Fixture f = fixture();
        AgentIdentity agA = agent(f.agentId(), f.ownerUserId());
        var started = startService.start(agA, startReq("重新签发", f.channelId()));
        Long oldTokenId = tailTokenId(started.postTicket());

        var outcome = reissueService.reissue(f.ownerUserId(), started.postTicket());

        assertThat(outcome.handoffToken()).isNotBlank()
                .isNotEqualTo(started.nextHandoffToken());          // 是全新的一根
        assertThat(tokenStatus(oldTokenId)).isEqualTo("REVOKED");   // 旧的真的被吊销
        assertThat(tailTokenId(started.postTicket())).isNotEqualTo(oldTokenId);

        // ★ 旧明文从此不可用——若不吊销，它流到别人手里就是一个后门
        assertThatThrownBy(() -> claimHandoffService.claim(
                agent(f.agent2Id(), f.ownerUserId()),
                new ClaimHandoffRequest(started.nextHandoffToken())))
                .isInstanceOf(ApiException.class);

        // 新令牌可用
        assertThat(claimHandoffService.claim(agent(f.agent2Id(), f.ownerUserId()),
                new ClaimHandoffRequest(outcome.handoffToken()))).isNotNull();
    }

    // ═══════════════ ⑤ L16 遗留 bug 的回归测试 ═══════════════

    /**
     * 🔴 <b>修前必红</b>：第 2 棒开始写作时，会话必须回到 {@code RUNNING}。
     *
     * <p>L16 的 {@code SubmitContributionService} 注释白纸黑字写着「等下一棒 claim lease 时
     * 再回到 RUNNING」，但 {@code ClaimLeaseService} 里<b>只有 {@code OPEN → RUNNING} 一个分支</b>——
     * 那个「再回到」<b>从来没有被实现过</b>。于是从第 2 棒起，机娘正在写的时候
     * 会话永远停在 {@code AWAITING_CONTINUATION}（"等人来接"）。
     *
     * <p>★ 为什么它能潜伏两课、两轮测试都没红：核实过——{@code session.status} 在整个模块里
     * <b>被写 4 处、被读来做判定 0 处</b>，没有任何 SQL 的 WHERE 用到它。
     * <b>一个从来没被读过的状态，写错了也没人会发现。</b>
     * <p>L17 说「状态不准是会骗人的」，这里是更狠的后半截：
     * <b>没人读的状态连骗人的机会都没有，它只是静静地错着——直到有人把它显示出来。</b>
     * 而 L18 的时间线页正是它的第一个真正消费者。
     */
    @Test
    void sessionReturnsToRunningWhenSecondTurnStartsWriting() {
        Fixture f = fixture();
        AgentIdentity agA = agent(f.agentId(), f.ownerUserId());
        AgentIdentity agB = agent(f.agent2Id(), f.ownerUserId());

        var started = startService.start(agA, startReq("会话状态回归", f.channelId()));
        String t1 = started.contributionTicket().ticketCode();
        var lease1 = claimLeaseService.claim(agA, t1);
        assertSessionStatus(started.postTicket(), "RUNNING");        // 首棒：本来就对

        submitService.submit(agA, t1, lease1.leaseToken(), new SubmitContributionRequest("第一棒", null));
        assertSessionStatus(started.postTicket(), "AWAITING_CONTINUATION");

        var join2 = claimHandoffService.claim(agB, new ClaimHandoffRequest(started.nextHandoffToken()));
        claimLeaseService.claim(agB, join2.contributionTicket().ticketCode());

        // ★ 修复前这里是 AWAITING_CONTINUATION —— 有人正在写，状态却说"等人来接"
        assertSessionStatus(started.postTicket(), "RUNNING");
    }

    // ═══════════════ ⑥ 时间线 ═══════════════

    /**
     * 时间线必须包含<b>全过程</b>，而不只是「提交」与「超时」。
     *
     * <p>{@code COLLAB_STARTED / HANDOFF_CLAIMED / LEASE_CLAIMED} 三个动作从 V014 起就写在
     * CHECK 值集里，却<b>从来没有代码产生过</b>——在 L18 之前，时间线页会显示一条协作
     * <b>凭空从「第 1 棒提交成功」开始</b>。
     * <p>★ 教训：<b>CHECK 值集里有个值，不等于有代码会产生它。值集是承诺，不是实现。</b>
     */
    @Test
    void timelineContainsWholeStoryNotJustSubmits() {
        Scenario s = scenarioWithTimedOutMiddleTurn("完整时间线");
        retryService.retry(s.ownerUserId(), s.postTicket(), s.deadTicket());

        // 审计由 @ApplicationModuleListener 异步写入（事务性发件箱），要等一下。
        await(8000, () -> timelineService.get(s.ownerUserId(), s.postTicket())
                .timeline().stream().anyMatch(e -> "TICKET_RETRIED".equals(e.actionType())));

        CollaborationDetailView view = timelineService.get(s.ownerUserId(), s.postTicket());
        List<String> actions = view.timeline().stream()
                .map(CollaborationDetailView.TimelineEntry::actionType).toList();

        assertThat(actions)
                .contains("COLLAB_STARTED")          // ← 本课才第一次有人产生
                .contains("HANDOFF_CLAIMED")         // ← 同上
                .contains("LEASE_CLAIMED")           // ← 同上
                .contains("CONTRIBUTION_SUBMITTED")
                .contains("ATTEMPT_EXPIRED")
                .contains("TICKET_RETRIED");         // ← L18 新增

        // 事故报告带建议动作，前端按钮据此渲染（不写死）
        assertThat(view.errors()).isNotEmpty();
        assertThat(view.errors().get(0).suggestedActions()).contains("RETRY_TICKET");
        // 席位与尝试都在
        assertThat(view.tickets()).hasSize(3);
        assertThat(view.tickets()).anySatisfy(t -> assertThat(t.attempts()).isNotEmpty());
    }

    // ═══════════════ ⑦ 机娘自报失败 ═══════════════

    /**
     * 自报失败与 Worker 超时走<b>同一套状态推进</b>（共用 {@code FailurePropagation}），
     * 区别只有触发者与失败原因。
     *
     * <p>★ 单开这个端点的主要收益不是省那 15 分钟，而是<b>把「症状」换成「原因」</b>：
     * 超时那条只能写「租约超时」（服务端不知道为什么），自报带着机娘自己说的理由。
     */
    @Test
    void clientReportedFailurePropagatesLikeTimeoutButKeepsRealReason() {
        Fixture f = fixture();
        AgentIdentity agA = agent(f.agentId(), f.ownerUserId());
        AgentIdentity agB = agent(f.agent2Id(), f.ownerUserId());

        var started = startService.start(agA, startReq("自报失败", f.channelId()));
        String t1 = started.contributionTicket().ticketCode();
        var lease1 = claimLeaseService.claim(agA, t1);
        submitService.submit(agA, t1, lease1.leaseToken(),
                new SubmitContributionRequest("第一棒", null));

        var join2 = claimHandoffService.claim(agB, new ClaimHandoffRequest(started.nextHandoffToken()));
        String t2 = join2.contributionTicket().ticketCode();
        var join3 = claimHandoffService.claim(agA, new ClaimHandoffRequest(join2.nextHandoffToken()));
        String t3 = join3.contributionTicket().ticketCode();

        var lease2 = claimLeaseService.claim(agB, t2);
        reportFailureService.reportFailure(agB, t2, lease2.leaseToken(), "上一棒缺少关键前提，无法续写");

        assertAnyAttemptStatus(t2, "FAILED_CLIENT");             // ★ 值集里躺了两课的死状态，终于有人产生
        assertTicketStatus(t2, "FAILED_TIMEOUT");
        assertTicketStatus(t3, "BLOCKED_BY_PREDECESSOR");        // 与超时同样阻塞后序
        assertSessionStatus(started.postTicket(), "PAUSED_ON_ERROR");

        // 事故报告里记的是【真实原因】，不是「租约超时」
        await(8000, () -> !jdbc.queryForList(
                "SELECT id FROM error_report WHERE error_type='CLIENT_REPORTED_FAILURE'").isEmpty());
        String summary = jdbc.queryForObject(
                "SELECT summary FROM error_report WHERE error_type='CLIENT_REPORTED_FAILURE' "
                        + "ORDER BY id DESC LIMIT 1", String.class);
        assertThat(summary).contains("上一棒缺少关键前提");

        // 自报失败之后照样可以 retry —— 它和超时落到同一个局面
        retryService.retry(f.ownerUserId(), started.postTicket(), t2);
        assertTicketStatus(t2, "READY_TO_WRITE");
    }

    // ══════════════════════ 夹具与断言 ══════════════════════

    /** 一个「首棒完成、中间棒超时、第三棒被阻塞」的标准局面——本课绝大多数测试的起点。 */
    private record Scenario(Long ownerUserId, String postTicket,
                            String doneTicket, String deadTicket, String blockedTicket,
                            AgentIdentity deadTicketAgent, AgentIdentity otherAgent) {}

    private Scenario scenarioWithTimedOutMiddleTurn(String title) {
        Fixture f = fixture();
        AgentIdentity agA = agent(f.agentId(), f.ownerUserId());
        AgentIdentity agB = agent(f.agent2Id(), f.ownerUserId());

        var started = startService.start(agA, startReq(title, f.channelId()));
        String t1 = started.contributionTicket().ticketCode();
        var lease1 = claimLeaseService.claim(agA, t1);
        submitService.submit(agA, t1, lease1.leaseToken(),
                new SubmitContributionRequest("第一棒的内容", null));

        var join2 = claimHandoffService.claim(agB, new ClaimHandoffRequest(started.nextHandoffToken()));
        String t2 = join2.contributionTicket().ticketCode();
        var join3 = claimHandoffService.claim(agA, new ClaimHandoffRequest(join2.nextHandoffToken()));
        String t3 = join3.contributionTicket().ticketCode();

        claimLeaseService.claim(agB, t2);
        forceExpireAttempt(t2);
        worker.sweepOnce();

        return new Scenario(f.ownerUserId(), started.postTicket(), t1, t2, t3, agB, agA);
    }

    private Fixture fixture() {
        int n = SEQ.incrementAndGet();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO user_account(username,email,password_hash,status,created_at,updated_at) "
                        + "VALUES (?,?,'{noop}x','ACTIVE',?,?)",
                "o18-" + n, "o18-" + n + "@example.com", now, now);
        Long owner = jdbc.queryForObject("SELECT id FROM user_account WHERE email=?",
                Long.class, "o18-" + n + "@example.com");

        jdbc.update("INSERT INTO forum_channel(slug,name,sort_order,enabled,post_count,created_at,updated_at) "
                + "VALUES (?,?,0,TRUE,0,?,?)", "o18-ch-" + n, "L18测试", now, now);
        Long ch = jdbc.queryForObject("SELECT id FROM forum_channel WHERE slug=?",
                Long.class, "o18-ch-" + n);

        jdbc.update("INSERT INTO agent_account(owner_user_id,nickname,status,created_at,updated_at) "
                + "VALUES (?,?,'ACTIVE',?,?)", owner, "o18-A-" + n, now, now);
        Long agA = jdbc.queryForObject("SELECT id FROM agent_account WHERE nickname=?",
                Long.class, "o18-A-" + n);

        jdbc.update("INSERT INTO agent_account(owner_user_id,nickname,status,created_at,updated_at) "
                + "VALUES (?,?,'ACTIVE',?,?)", owner, "o18-B-" + n, now, now);
        Long agB = jdbc.queryForObject("SELECT id FROM agent_account WHERE nickname=?",
                Long.class, "o18-B-" + n);

        return new Fixture(owner, ch, agA, agB);
    }

    private AgentIdentity agent(Long agentId, Long ownerId) {
        return new StubAgent(agentId, ownerId, 1L, "codex", "run-" + agentId);
    }

    private StartCollaborationRequest startReq(String title, Long channelId) {
        return new StartCollaborationRequest(title, channelId, null, null);
    }

    private void forceExpireAttempt(String ticketCode) {
        jdbc.update("UPDATE contribution_attempt ca JOIN contribution_ticket ct ON ca.ticket_id=ct.id "
                        + "SET ca.lease_expires_at=? WHERE ct.ticket_code=? AND ca.status='ACTIVE'",
                Instant.now().minus(Duration.ofSeconds(1)), ticketCode);
    }

    private void assertTicketStatus(String ticketCode, String expected) {
        String s = jdbc.queryForObject(
                "SELECT status FROM contribution_ticket WHERE ticket_code=?", String.class, ticketCode);
        assertThat(s).as("ticket %s", ticketCode).isEqualTo(expected);
    }

    /** ★ 用 anyMatch 而不是 queryForObject：retry 之后一张票有<b>多条</b> attempt。 */
    private void assertAnyAttemptStatus(String ticketCode, String expected) {
        List<String> all = jdbc.queryForList(
                "SELECT ca.status FROM contribution_attempt ca "
                        + "JOIN contribution_ticket ct ON ct.id=ca.ticket_id WHERE ct.ticket_code=?",
                String.class, ticketCode);
        assertThat(all).as("attempts of %s", ticketCode).contains(expected);
    }

    private void assertSessionStatus(String postTicket, String expected) {
        String s = jdbc.queryForObject(
                "SELECT status FROM collaboration_session WHERE post_ticket=?", String.class, postTicket);
        assertThat(s).as("session %s", postTicket).isEqualTo(expected);
    }

    private Long tailTokenId(String postTicket) {
        return jdbc.queryForObject(
                "SELECT tail_handoff_token_id FROM collaboration_session WHERE post_ticket=?",
                Long.class, postTicket);
    }

    private String tokenStatus(Long tokenId) {
        return jdbc.queryForObject("SELECT status FROM handoff_token WHERE id=?", String.class, tokenId);
    }

    private void assertHandoffStatusOfTail(String postTicket, String expected) {
        assertThat(tokenStatus(tailTokenId(postTicket))).as("tail handoff of %s", postTicket)
                .isEqualTo(expected);
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
