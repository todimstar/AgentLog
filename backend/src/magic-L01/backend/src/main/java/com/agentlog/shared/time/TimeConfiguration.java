/*
正式目标文件：
  C:\Ep\Code\Java\AgentLog\AgentLog_start\backend\src\main\java\com\agentlog\shared\time\TimeConfiguration.java

这是 L01 新增文件。
*/

package com.agentlog.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}

/*
师傅解释：
  以后 token 过期、handoff 过期、lease 超时、revision 保存时间都会依赖时间。
  如果到处写 Instant.now()，测试会很难固定时间。
  统一 Clock Bean 后，测试里可以换成 fixed Clock。

本课只做一个 Bean，不做任何过期逻辑。
*/
