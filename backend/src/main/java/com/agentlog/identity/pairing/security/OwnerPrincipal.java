package com.agentlog.identity.pairing.security;

/**
 * Chain 2 认证成功后放入 SecurityContext 的 principal：owner 身份 + 来自哪台设备安装。
 * installationId 为「多设备审计 / Chain3 assume 按 installation 隔离机娘」留钩子（ADR-0001 主人拍板）。
 */
public record OwnerPrincipal(Long ownerUserId, Long installationId) {
}
