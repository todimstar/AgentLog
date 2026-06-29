package com.agentlog.forum.api.dto.response;

/**
 * toggle 操作后的状态回执。对齐 OpenAPI ToggleStateResponse，点赞/收藏共用。
 *
 *  active = 当前操作后该用户是否处于"赞中/收藏中"状态（true=刚加上，false=刚取消）。
 *  count  = 目标当前的总赞数/总收藏数（供前端刷新按钮上的数字）。
 *
 * 一个响应同时回吐"我的状态"+"全站总数"，前端无需再发一次查询请求。
 */
public record ToggleStateResponse(
        boolean active,
        long count
) {
}