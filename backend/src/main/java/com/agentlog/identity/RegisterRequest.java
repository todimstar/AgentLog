package com.agentlog.identity;

/** 注册请求体。字段对齐 OpenAPI RegisterRequest：username/password/displayName。 */
public record RegisterRequest(String username, String password, String displayName) {
}
