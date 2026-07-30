package com.agentlog.collaboration;

import com.agentlog.collaboration.api.dto.request.ClaimHandoffRequest;
import com.agentlog.collaboration.api.dto.request.StartCollaborationRequest;
import com.agentlog.collaboration.api.dto.response.StartCollaborationResponse;
import com.agentlog.collaboration.application.ClaimHandoffService;
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
 * ★★★ L15 皇冠验收：同一张接力棒被多个线程同时抢，【只有一个能成功】★★★
 *
 * 对应 course_schedule L15 的硬验收「同 token 双抢一成功」，以及
 * Pack {@code 11-testing/并发测试清单.md} 第 1 条「两线程 claim 同一 HandoffToken，只成功一个」。
 *
 * <p><b>为什么这个测试必须存在</b>：被测的不是某个函数的返回值，而是一条 SQL 的<b>并发语义</b>。
 * 单线程跑一万遍都证明不了它——把「查-判-改」三步走换上来，本类的所有断言依然会绿，
 * 只有真并发才能把那个空窗暴露出来。安全与一致性的防线，必须由并发测试守。
 *
 * <p><b>为什么不用 MockMvc</b>：这里要压的是 Service + SQL 的并发行为，
 * HTTP 层只会引入无关噪音（线程池、序列化）。直接注入 Service，最短路径打到那条 UPDATE。
 *
 * <p><b>为什么本类不加 {@code @Transactional}</b>（关键，很容易写错）：
 * 测试类若带事务，所有线程会共享同一个测试事务/连接，根本形成不了两个独立事务的竞争——
 * 测试会"绿"，但绿得毫无意义。这里让每个线程各自走 Service 的 {@code @Transactional}，
 * 从连接池各拿一条连接，才是真并发。
 *
 * <p><b>为什么用 Testcontainers 真 MySQL</b>：行锁与条件 UPDATE 的语义是数据库实现的，
 * H2 之类的内存库对不上——「测试绿、生产挂」正是这么来的。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class HandoffConcurrencyIntegrationTest {

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

    @Autowired private StartCollaborationService startService;
    @Autowired private ClaimHandoffService claimService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 两个线程同抢一张令牌：恰好 1 成功 + 1 拿到 409 ACPP_HANDOFF_CONSUMED，且库里只多出 1 张票。 */
    @Test
    void twoThreadsClaimingSameTokenOnlyOneWins() throws Exception {
        runContention(2);
    }

    /**
     * 加压到 8 个线程仍然只成功一个。
     *
     * 2 个线程有可能"侥幸错开"而看不出竞态（尤其在慢机器上），线程数越多越容易命中那个窗口。
     * 这条是防止竞态测试沦为"看起来测过了"的常见手法。
     */
    @Test
    void eightThreadsClaimingSameTokenStillOnlyOneWins() throws Exception {
        runContention(8);
    }

    private void runContention(int threads) throws Exception {
        Fixture f = fixture();

        // 开局：拿到第一根悬空尾令牌的明文。
        StartCollaborationResponse started = startService.start(
                agent(f.agentIds.get(0), f.ownerUserId),
                new StartCollaborationRequest("并发抢棒", f.channelId, null, null));
        String handoff = started.nextHandoffToken();

        // 起跑门：所有线程先在 latch 上等齐，再同时冲——最大化撞进同一个时间窗。
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            // 每个线程用【不同的机娘】抢——更贴近真实场景（主人把同一根棒子发给了多个 AI 对话）。
            Long agentId = f.agentIds.get(i % f.agentIds.size());
            Callable<Outcome> task = () -> {
                startGate.await();
                try {
                    StartCollaborationResponse r = claimService.claim(
                            agent(agentId, f.ownerUserId), new ClaimHandoffRequest(handoff));
                    return new Outcome(true, null, r.contributionTicket().ticketCode());
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
                .as("同一张接力棒，%d 个线程同抢，必须恰好一个成功", threads)
                .hasSize(1);

        // ② 所有败者都拿到干净的 409 ACPP_HANDOFF_CONSUMED——
        //    【不是】DuplicateKeyException 之类的约束冲突。这一条正是「闸门必须在建票之前」的守卫：
        //    若把顺序写成「查 → 建票 → 消费」，败者会先撞 uk_ticket_sequence，这里就会红。
        Assertions.assertThat(outcomes.stream().filter(o -> !o.won()).map(Outcome::code))
                .as("败者必须拿到语义清晰的 409，而不是底层约束异常")
                .isNotEmpty()
                .allMatch("ACPP_HANDOFF_CONSUMED"::equals);

        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM collaboration_session WHERE post_ticket = ?",
                Long.class, started.postTicket());

        // ③ 库里只多出一张票：首棒 + 赢家那张 = 2。
        Integer ticketCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contribution_ticket WHERE session_id = ?", Integer.class, sessionId);
        Assertions.assertThat(ticketCount).as("席位总数").isEqualTo(2);

        // ④ 第 2 棒有且只有一张（uk_ticket_sequence 是安全网，但正常路径根本不该触发它）。
        Integer seq2Count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contribution_ticket WHERE session_id = ? AND sequence_no = 2",
                Integer.class, sessionId);
        Assertions.assertThat(seq2Count).isEqualTo(1);

        // ⑤ 令牌账目清清楚楚：恰好 1 张 CONSUMED（被抢走的）+ 1 张 AVAILABLE（换发的新尾令牌）。
        Integer consumed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM handoff_token WHERE session_id = ? AND status = 'CONSUMED'",
                Integer.class, sessionId);
        Integer available = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM handoff_token WHERE session_id = ? AND status = 'AVAILABLE'",
                Integer.class, sessionId);
        Assertions.assertThat(consumed).as("被消费的令牌数").isEqualTo(1);
        Assertions.assertThat(available).as("可用的悬空尾令牌数").isEqualTo(1);

        // ⑥ 消费痕迹与赢家一致：consumed_ticket_id 指向的正是赢家拿到的那张票。
        String consumedTicketCode = jdbcTemplate.queryForObject(
                "SELECT ct.ticket_code FROM handoff_token h "
                        + "JOIN contribution_ticket ct ON ct.id = h.consumed_ticket_id "
                        + "WHERE h.session_id = ? AND h.status = 'CONSUMED'",
                String.class, sessionId);
        Assertions.assertThat(consumedTicketCode).isEqualTo(winners.get(0).ticketCode());
    }

    // —— 夹具（不走 HTTP，直接插表 + 用桩 principal）——

    /**
     * 直接构造 AgentIdentity 的桩。
     *
     * 这正是 L14 把机娘身份定义成 shared 里的【只读接口】（而不是 identity 的具体 principal 类）
     * 换来的一个额外好处：测试里一个 record 就能实现它，不必跑完整条配对 + assume 的认证流。
     * 依赖倒置的收益，除了守模块边界，还包括"可替身"。
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
        jdbcTemplate.update(
                "INSERT INTO user_account(username,email,password_hash,status,created_at,updated_at) "
                        + "VALUES (?,?,'{noop}x','ACTIVE',?,?)", "conc-" + n, "conc-" + n + "@example.com", now, now);
        Long ownerUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE email=?", Long.class, "conc-" + n + "@example.com");

        jdbcTemplate.update(
                "INSERT INTO forum_channel(slug,name,sort_order,enabled,post_count,created_at,updated_at) "
                        + "VALUES (?,?,0,TRUE,0,?,?)", "conc-ch-" + n, "开发日志", now, now);
        Long channelId = jdbcTemplate.queryForObject(
                "SELECT id FROM forum_channel WHERE slug=?", Long.class, "conc-ch-" + n);

        List<Long> agentIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String nickname = "conc-agent-" + n + "-" + i;
            jdbcTemplate.update(
                    "INSERT INTO agent_account(owner_user_id,nickname,status,created_at,updated_at) "
                            + "VALUES (?,?,'ACTIVE',?,?)", ownerUserId, nickname, now, now);
            agentIds.add(jdbcTemplate.queryForObject(
                    "SELECT id FROM agent_account WHERE owner_user_id=? AND nickname=?",
                    Long.class, ownerUserId, nickname));
        }
        return new Fixture(ownerUserId, channelId, agentIds);
    }

    private record Fixture(Long ownerUserId, Long channelId, List<Long> agentIds) {
    }

    /** 一个线程的抢棒结果：赢了没、失败错误码、赢到的票号。 */
    private record Outcome(boolean won, String code, String ticketCode) {
    }
}
