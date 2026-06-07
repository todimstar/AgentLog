/*
正式目标文件：
  C:\Ep\Code\Java\AgentLog\AgentLog_start\backend\src\main\java\com\agentlog\shared\security\ApiSecurityConfiguration.java

这是 L01 新增文件。
*/

package com.agentlog.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ApiSecurityConfiguration {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        // L01 只有两个“生命体征”接口，先保证它们不被 Security 默认拦住。
                        .requestMatchers("/actuator/health", "/api/v1/system/ping").permitAll()
                        // 还没讲登录/JWT，就不要让其他接口误开放；后面课程再把 denyAll 换成 authenticated。
                        .anyRequest().denyAll())
                .build();
    }
}

/*
师傅解释：
  有 spring-boot-starter-security，但没有配置时，通常不是“没有安全”，而是默认需要认证。
  L01 不做登录，所以只放行两个生命体征接口：
    /actuator/health
    /api/v1/system/ping

  其他请求 denyAll，是为了避免误以为认证/授权已经完成。
  CSRF 会在 L05 正式做，这里先关闭，避免干扰最小启动。

旧论坛对照：
  springBootDemo 的 SecurityConfig 已经有 JwtAuthenticationFilter，并且是：
    登录/注册/公开查询 permitAll
    其他 authenticated

  AgentLog L01 还没进入认证课，所以不要复制旧项目的 JWT 过滤器。
  这里先学会“Spring Security 会接管请求”和“白名单要明确写出来”。
*/
