package com.agentlog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class AgentLogApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentLogApplication.class, args);
    }
}
