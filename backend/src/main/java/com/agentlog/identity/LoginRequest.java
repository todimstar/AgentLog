package com.agentlog.identity;

/** 登录请求体。字段对齐 OpenAPI LoginRequest：username/password。 */
public record LoginRequest(String username, String password) {
}
