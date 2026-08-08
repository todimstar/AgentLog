package com.agentlog.shared.idempotency;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等台账的读写。<b>每个写方法都跑在自己的独立事务里</b>（{@code REQUIRES_NEW}）。
 *
 * <h3>★ 为什么必须独立事务（本课最容易写错的地方）</h3>
 * 假设台账与业务共用同一个事务：
 * <pre>
 *   请求A: INSERT 台账(IN_PROGRESS) ──> 执行业务（假设 30 秒）──> UPDATE 台账(COMPLETED)
 *   请求B(同 key，1 秒后到): INSERT 台账 ──> ???
 * </pre>
 * <ul>
 *   <li><b>共用事务</b>：A 的 INSERT 尚未提交 → B 的 INSERT 撞唯一键后会<b>被行锁挂住干等 30 秒</b>，
 *       最后可能拿到 {@code Lock wait timeout exceeded}；这期间 B 还白占着一条数据库连接。</li>
 *   <li><b>独立事务</b>：A 的台账<b>立刻可见</b> → B 立刻查到 IN_PROGRESS →
 *       立刻返回 409「同 key 处理中」。</li>
 * </ul>
 * 一句话：<b>「处理中」这个状态必须对并发者立刻可见，幂等才成立。藏在未提交的事务里，它等于不存在。</b>
 *
 * <h3>为什么 try-catch 写在切面里而不是这里</h3>
 * {@link #begin} 撞唯一键会抛 {@code DuplicateKeyException}，而<b>此时它所在的那个事务已被标记
 * rollback-only</b> —— 在方法内部 catch 之后再做任何数据库操作都会失败。
 * 所以捕获必须发生在<b>事务边界之外</b>（切面里），让这个小事务先干净地回滚掉。
 *  * 这正是 L15 学到的那条教训的另一面：撞唯一键之后事务就脏了。
 */
@Component
public class IdempotencyStore {

    /** 台账保留时长（Pack {@code 10-reliability/idempotency.md}：默认 24 小时，每日清理）。 */
    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyRecordMapper mapper;
    private final Clock clock;

    public IdempotencyStore(IdempotencyRecordMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * ★ 抢占式 INSERT：直接写，撞唯一键就说明「有人先来了」。
     *
     * <p>★ 这里靠唯一键判定是<b>正解</b>，而 claim lease 靠 {@code uk_attempt_ticket_no}
     * 判定是<b>反模式</b> —— 判据是同一条：
     * <blockquote>
     * 那个唯一键守的不变量，是不是<b>就是</b>你此刻要判定的那件事，且<b>覆盖全部失败情形</b>？
     * </blockquote>
     * 幂等要判定的全部内容就是「这个 key 来过没有」，而 {@code uk_idempotency} 守的正是这件事，
     * 没有第二种「来过」的方式 → 覆盖 100%，所以它就是天生的判定器。
     * 而 claim lease 要判定的是「这张票能不能领」（状态 + 归属 + 时效），
     * 唯一键只挡得住「两个人抢同一张票」，挡不住「票不是你的 / 前序没写完 / 会话已终止」。
     *
     * <p>与 L15 的条件 UPDATE 是同一思想的两种形态：
     * <pre>
     *   要改已有的行 → 条件 UPDATE，affectedRows 裁决
     *   要建全新的行 → 抢占 INSERT，唯一键冲突裁决
     * </pre>
     * 共同点：让数据库在<b>一条语句内</b>完成「检查 + 动作」，中间不留 TOCTOU 缝隙。
     *
     * @throws org.springframework.dao.DuplicateKeyException 同 key 已存在（调用方须在事务外捕获）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecordDO begin(Long ownerUserId, Long agentId, String endpoint,
                                     String idempotencyKey, String requestHash) {
        Instant now = Instant.now(clock);
        IdempotencyRecordDO record = new IdempotencyRecordDO();
        record.setOwnerUserId(ownerUserId);
        record.setAgentId(agentId);
        record.setEndpoint(endpoint);
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestHash(requestHash);
        record.setStatus(IdempotencyRecordDO.Status.IN_PROGRESS.getCode());
        record.setExpiresAt(now.plus(RETENTION));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        mapper.insert(record);
        return record;
    }

    /** 查已有台账（抢占失败后用来判定「重放」还是「key 撞车」）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public IdempotencyRecordDO find(Long ownerUserId, Long agentId, String endpoint, String idempotencyKey) {
        return mapper.selectOne(Wrappers.<IdempotencyRecordDO>lambdaQuery()
                .eq(IdempotencyRecordDO::getOwnerUserId, ownerUserId)
                .eq(IdempotencyRecordDO::getAgentId, agentId)
                .eq(IdempotencyRecordDO::getEndpoint, endpoint)
                .eq(IdempotencyRecordDO::getIdempotencyKey, idempotencyKey));
    }

    /**
     * 业务成功：落 COMPLETED 并缓存响应体。
     *
     * <p>⚠️ <b>诚实登记的窗口</b>：业务事务已提交、但本方法执行前进程崩溃 →
     * 台账停在 IN_PROGRESS，客户端重试会拿到 409，可事情其实<b>已经做成了</b>。
     * 这是 at-most-once 的固有窗口，无法用单机事务消除（要消除得上两阶段提交，代价远大于收益）。
     * 客户端的自愈路径是查席位状态：{@code GET /agent/contribution-tickets/{ticketCode}}
     * 显示 DONE 就说明上次成功了。24h 后台账过期自愈。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long id, int responseStatus, String responseBodyJson) {
        IdempotencyRecordDO record = new IdempotencyRecordDO();
        record.setId(id);
        record.setStatus(IdempotencyRecordDO.Status.COMPLETED.getCode());
        record.setResponseStatus(responseStatus);
        record.setResponseBodyJson(responseBodyJson);
        record.setUpdatedAt(Instant.now(clock));
        mapper.updateById(record);
    }

    /**
     * 业务失败：<b>删掉</b>台账，让客户端能用同一个 key 重试。
     *
     * <p>为什么是删除而不是标记 FAILED：幂等保护的是「已经生效的操作不要重复生效」；
     * 业务失败意味着<b>什么都没生效</b>，此时应当允许原样重试。
     * 留一条 FAILED 记录只会让客户端拿着同一个 key 永远撞墙。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(Long id) {
        mapper.deleteById(id);
    }
}
