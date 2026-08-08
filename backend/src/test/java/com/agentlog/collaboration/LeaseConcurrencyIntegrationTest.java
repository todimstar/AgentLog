package com.agentlog.collaboration;

import com.agentlog.collaboration.api.dto.request.StartCollaborationRequest;
import com.agentlog.collaboration.api.dto.response.ClaimLeaseResponse;
import com.agentlog.collaboration.api.dto.response.StartCollaborationResponse;
import com.agentlog.collaboration.application.ClaimLeaseService;
import com.agentlog.collaboration.application.StartCollaborationService;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.security.AgentIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
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

/**
 * ★★★ L16 皇冠验收：同一张席位的租约被多个线程同时领，<b>只有一个能成功</b> ★★★
 *
 * 对应 {@code course_schedule} L16 的硬验收「双领一成功」，以及
 * Pack {@code 11-testing/并发测试清单.md} 第 2 条「两线程 claim 同一 Ticket Lease，只成功一个」。
 *
 * <h3>与 L15 那条并发测试的区别</h3>
 * L15 是<b>不同机娘</b>抢同一根接力棒（主人把棒子发给了多个 AI 对话）；
 * 这里是<b>同一个机娘</b>的多个进程/重试抢同一张票的租约——因为 {@code required_agent_id}
 * 已经把席位钉死在一个机娘身上，别人根本抢不到（那是 403，不是竞态）。
 * 真实场景：CLI 被手滑执行了两遍、脚本重试、或者同一个机娘开了两个终端。
 *
 * <h3>为什么这个测试必须存在</h3>
 * 被测的不是某个函数的返回值，而是一条 SQL 的<b>并发语义</b>。
 * 把闸门顺序改成「查票 → 建 attempt → 改票状态」，单线程测试<b>全部照绿</b>，
 * 只有真并发才能把那个空窗暴露出来。
 *
 * <p><b>本类不加 {@code @Transactional}</b>（关键，很容易写错）：测试类若带事务，
 * 所有线程共享同一个测试事务与连接，根本形成不了两个独立事务的竞争——测试会绿，但绿得毫无意义。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LeaseConcurrencyIntegrationTest {

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

    @Autowired private StartCollaborationService startService;
    @Autowired private ClaimLeaseService claimLeaseService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 两个线程同领一张票的租约：恰好 1 成功 + 1 拿到干净的 409。 */
    @Test
    void twoThreadsClaimingSameLeaseOnlyOneWins() throws Exception {
        runContention(2);
    }

    /**
     * 加压到 8 个线程仍然只成功一个。
     *
     * 2 个线程有可能"侥幸错开"而看不出竞态（尤其在慢机器上），线程数越多越容易命中那个窗口。
     * 这是防止竞态测试沦为"看起来测过了"的常见手法（L15 同款）。
     */
    @Test
    void eightThreadsClaimingSameLeaseStillOnlyOneWins() throws Exception {
        runContention(8);
    }

    private void runContention(int threads) throws Exception {
        Fixture f = fixture();
        AgentIdentity principal = agent(f.agentId, f.ownerUserId);

        // 开局：拿到一张 READY_TO_WRITE 的首棒席位。
        StartCollaborationResponse started = startService.start(
                principal, new StartCollaborationRequest("并发领租约", f.channelId, null, null));
        String ticketCode = started.contributionTicket().ticketCode();

        // 起跑门：所有线程先在 latch 上等齐，再同时冲——最大化撞进同一个时间窗。
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Outcome> task = () -> {
                startGate.await();
                try {
                    ClaimLeaseResponse r = claimLeaseService.claim(principal, ticketCode);
                    return new Outcome(true, null, r.leaseToken());
                } catch (ApiException e) {
                    return new Outcome(false, e.code(), null);
                }
            };
            futures.add(pool.submit(task));
        }
        startGate.countDown();
        pool.shutdown();
        Assertions.assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get());
        }

        // ① 恰好一个赢家。
        List<Outcome> winners = outcomes.stream().filter(Outcome::won).toList();
        Assertions.assertThat(winners)
                .as("同一张席位的租约，%d 个线程同领，必须恰好一个成功", threads)
                .hasSize(1);

        // ② ★★ 本类最关键的一条断言 ★★
        //    所有败者拿到的必须是干净的 409 ACPP_LEASE_ALREADY_CLAIMED，
        //    【不是】DuplicateKeyException 之类的底层约束异常（那会变成 HTTP 500）。
        //    这正是「闸门必须排在建 attempt 之前」的守卫：
        //    若把顺序写成「查票 → 建 attempt → 改票」，败者会先撞 uk_attempt_ticket_no，这里立刻红。
        Assertions.assertThat(outcomes.stream().filter(o -> !o.won()).map(Outcome::code))
                .as("败者必须拿到语义清晰的 409，而不是底层约束异常")
                .isNotEmpty()
                .allMatch("ACPP_LEASE_ALREADY_CLAIMED"::equals);

        Long ticketId = jdbcTemplate.queryForObject(
                "SELECT id FROM contribution_ticket WHERE ticket_code = ?", Long.class, ticketCode);

        // ③ 库里只有一条 attempt —— 唯一键是安全网，正常路径根本不该触发它。
        Assertions.assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM contribution_attempt WHERE ticket_id = ?", Integer.class, ticketId))
                .as("尝试记录数").isEqualTo(1);

        // ④ 席位落 LEASED，且 active_attempt_id 指向那唯一一条 attempt。
        Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM contribution_ticket WHERE id = ?", String.class, ticketId))
                .isEqualTo("LEASED");
        Assertions.assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM contribution_ticket t JOIN contribution_attempt a "
                                + "ON a.id = t.active_attempt_id WHERE t.id = ?", Integer.class, ticketId))
                .as("active_attempt_id 必须指向真实存在的那条 attempt（V013 补的外键）").isEqualTo(1);

        // ⑤ 只有赢家手里那张租约令牌能对上库里的摘要（其余线程根本没拿到令牌）。
        Assertions.assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM contribution_attempt WHERE ticket_id = ? "
                                + "AND status = 'ACTIVE' AND lease_token_digest IS NOT NULL",
                        Integer.class, ticketId))
                .isEqualTo(1);
        Assertions.assertThat(winners.get(0).leaseToken()).startsWith("lease_");

        // ⑥ 会话被推进到 RUNNING（首棒领租约 = 协作真正开始跑）。
        Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM collaboration_session WHERE post_ticket = ?",
                String.class, started.postTicket())).isEqualTo("RUNNING");
    }

    // —— 夹具（不走 HTTP，直接插表 + 用桩 principal）——

    /**
     * 直接构造 AgentIdentity 的桩 —— L14 把机娘身份定义成 shared 里【只读接口】换来的额外好处：
     * 测试里一个 record 就能实现它，不必跑完整条配对 + assume 的认证流。
     * 依赖倒置的收益除了守模块边界，还包括"可替身"。
     */
    private record StubAgent(Long agentAccountId, Long ownerUserId, Long installationId,
                             String sourceTool, String clientRunId) implements AgentIdentity {
    }

    private AgentIdentity agent(Long agentId, Long ownerUserId) {
        return new StubAgent(agentId, ownerUserId, 1L, "codex", "run-" + agentId);
    }

    private Fixture fixture() {
        int n = SEQ.incrementAndGet();
        Instant now = Instant.now();
        String email = "lease-conc-" + n + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO user_account(username,email,password_hash,status,created_at,updated_at) "
                        + "VALUES (?,?,'{noop}x','ACTIVE',?,?)", "lease-conc-" + n, email, now, now);
        Long ownerUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE email=?", Long.class, email);

        jdbcTemplate.update(
                "INSERT INTO forum_channel(slug,name,sort_order,enabled,post_count,created_at,updated_at) "
                        + "VALUES (?,?,0,TRUE,0,?,?)", "lease-conc-ch-" + n, "开发日志", now, now);
        Long channelId = jdbcTemplate.queryForObject(
                "SELECT id FROM forum_channel WHERE slug=?", Long.class, "lease-conc-ch-" + n);

        String nickname = "lease-conc-agent-" + n;
        jdbcTemplate.update(
                "INSERT INTO agent_account(owner_user_id,nickname,status,created_at,updated_at) "
                        + "VALUES (?,?,'ACTIVE',?,?)", ownerUserId, nickname, now, now);
        Long agentId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_account WHERE owner_user_id=? AND nickname=?",
                Long.class, ownerUserId, nickname);

        return new Fixture(ownerUserId, channelId, agentId);
    }

    private record Fixture(Long ownerUserId, Long channelId, Long agentId) {
    }

    /** 一个线程的领租约结果：赢了没、失败错误码、赢到的租约令牌。 */
    private record Outcome(boolean won, String code, String leaseToken) {
    }
}
