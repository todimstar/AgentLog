package com.agentlog.reliability.worker;

import com.agentlog.collaboration.CollaborationFacade;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期 Attempt 扫描 Worker（L17）。
 *
 * <p>★ 职责只有两件：① 拿活（批量锁定过期 attempt id）② 分发（逐条交给 service 处理）。
 * 不含任何业务逻辑——业务全在 {@link CollaborationFacade#expireAttempt}。
 *
 * <p>★ 「定时扫描」vs「立刻感知」：
 * 最坏情况是租约过期后最多 30 秒才被处理，这个延迟是体验参数而非安全参数——
 * 惰性判定（L16 的 lease_expires_at >= #{now} in WHERE）已经保证过期租约提交不进来；
 * Worker 只是把状态刷得好看，让时间线页看起来真实。
 * 改成 10 分钟也不会数据错乱，只是页面上多等一会儿。
 *
 * <p>★ 多实例不重复处理：
 * {@code lockExpiredAttempts} 用 {@code FOR UPDATE SKIP LOCKED}，
 * 同时运行的实例拿到不同批次的 id；{@code expireAttempt} 内的闸门再做最终校验。
 *
 * <p>★ 毒丸消息防线：
 * 每条 {@code expireAttempt} 是独立事务（REQUIRES_NEW），catch 到异常记日志后继续下一条，
 * 一条坏数据不会让整批永远卡死。
 */
@Component
public class ExpiredAttemptWorker {

    private static final Logger log = LoggerFactory.getLogger(ExpiredAttemptWorker.class);

    private final CollaborationFacade collaboration;
    private final Clock clock;
    private final int batchSize;

    public ExpiredAttemptWorker(CollaborationFacade collaboration, Clock clock,
            @Value("${agentlog.worker.expired-attempt.batch-size:50}") int batchSize) {
        this.collaboration = collaboration;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentlog.worker.expired-attempt.interval:PT30S}")
    public void sweep() {
        Instant now = Instant.now(clock);
        List<Long> ids = collaboration.lockExpiredAttempts(now, batchSize);
        if (ids.isEmpty()) return;

        log.info("[Worker] 本轮扫到 {} 条过期 attempt，开始处理", ids.size());
        int ok = 0, skip = 0;
        for (Long id : ids) {
            try {
                collaboration.expireAttempt(id);
                ok++;
            } catch (Exception e) {
                // ★ 毒丸防线：一条失败不影响其余。记日志后继续。
                log.error("[Worker] attempt {} 处理失败，跳过（将在下一轮重试）", id, e);
                skip++;
            }
        }
        log.info("[Worker] 本轮完成 {}/{}，跳过 {}", ok, ids.size(), skip);
    }

    /**
     * Dev 专用：手动触发一次 sweep，供 Postman 验收用（production profile 下不注册此端点）。
     *
     * <p>调用方：{@link com.agentlog.reliability.api.WorkerDevController#triggerSweep}
     */
    public void sweepOnce() {
        sweep();
    }
}
