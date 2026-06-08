package com.agentlog.shared.system;

import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final Clock clock;

    public SystemController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("ok", Instant.now(clock));
    }

    public record PingResponse(String status, Instant serverTime) {
    }
}
