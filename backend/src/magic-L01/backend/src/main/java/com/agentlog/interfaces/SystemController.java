/*
正式目标文件：
  C:\Ep\Code\Java\AgentLog\AgentLog_start\backend\src\main\java\com\agentlog\interfaces\SystemController.java

本文件只讲 L01 在 SystemController.java 的改动点。
*/

/*
定位 1：
  找到 import java.util.Map;

原来：
*/
import java.util.Map;

/*
改成：
*/
import java.time.Clock;
import java.time.Instant;

/*
师傅解释：
  不再直接返回 Map，是因为 L01 要把 Clock Bean 接入真实接口。
  serverTime 让我们能证明“统一时间源已经进入 Spring 调用链”。
*/


/*
定位 2：
  找到 class SystemController 的开头。

原来：
*/
public class SystemController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}

/*
改成：
*/
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

/*
师傅解释：
  status = ok：证明接口通。
  serverTime：证明 Clock Bean 被注入并使用。

本课边界：
  这里仍然只是系统探针，不是业务接口。
*/
