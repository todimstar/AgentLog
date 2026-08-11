package com.agentlog.reliability.api;

import com.agentlog.reliability.worker.ExpiredAttemptWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev 专用端点：手动触发一次 Worker sweep，供 Postman 验收用。
 *
 * <p>只在 {@code agentlog.worker.dev-trigger.enabled=true} 时激活（默认 false），
 * 生产环境不暴露此端点。
 */
@RestController
@RequestMapping("/api/v1/dev/worker")
@ConditionalOnProperty(name = "agentlog.worker.dev-trigger.enabled", havingValue = "true")
public class WorkerDevController {

    private final ExpiredAttemptWorker worker;

    public WorkerDevController(ExpiredAttemptWorker worker) {
        this.worker = worker;
    }

    @PostMapping("/sweep")
    public ResponseEntity<String> triggerSweep() {
        worker.sweepOnce();
        return ResponseEntity.ok("sweep triggered");
    }
}
