# 旧论坛对照：Security 与错误码

这页是课前热身。你之前在 `springBootDemo` 里已经做过类似问题，只是 AgentLog 这次换了边界和规范。

## 我对照了哪些旧文件

```text
C:\Ep\Code\Java\springBootDemo\community-backgroundend\src\main\java\com\liu\springbootdemo\config\SecurityConfig.java
C:\Ep\Code\Java\springBootDemo\community-backgroundend\src\main\java\com\liu\springbootdemo\POJO\Result\Result.java
C:\Ep\Code\Java\springBootDemo\community-backgroundend\src\main\java\com\liu\springbootdemo\POJO\Result\ErrorResponse.java
C:\Ep\Code\Java\springBootDemo\community-backgroundend\src\main\java\com\liu\springbootdemo\common\enums\ErrorCode.java
C:\Ep\Code\Java\springBootDemo\community-backgroundend\src\main\java\com\liu\springbootdemo\common\exception\GlobalExceptionHandler.java
```

## Security：像，但不要照抄

```text
旧论坛 SecurityConfig
  请求进来
    -> 白名单：登录、注册、Swagger、公开帖子/评论/分区
    -> 其他请求 authenticated
    -> JwtAuthenticationFilter 校验 token

AgentLog L01 ApiSecurityConfiguration
  请求进来
    -> 白名单：/actuator/health、/api/v1/system/ping
    -> 其他请求 denyAll
    -> 暂时没有登录、JWT、角色、会话
```

旧项目已经进入“用户系统 + JWT”阶段；L01 只是证明后端能启动。这里用 `denyAll` 不是偷懒，是课程边界：没讲认证前，不假装已经有认证。

## 错误码：这次不是旧 Result 包装

旧论坛大体是这样：

```json
{
  "code": 1,
  "message": "参数校验失败",
  "data": {
    "code": "30001",
    "message": "参数校验失败",
    "path": "/api/posts",
    "requestId": "..."
  }
}
```

这个写法能用，但它是项目自定义包装。AgentLog 设计文档要求的是 RFC 9457 风格 `application/problem+json`：

```json
{
  "type": "https://agentlog.local/problems/AUTH_SESSION_REQUIRED",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Session is required.",
  "code": "AUTH_SESSION_REQUIRED",
  "traceId": "trace-l01",
  "recoverable": true,
  "recoveryActions": ["LOGIN"]
}
```

这里的 `code` 不是最外层旧包装的成功/失败码，而是 ProblemDetail 里的项目扩展字段。标准字段负责让 HTTP 世界看懂，扩展字段负责让 AgentLog 前端、CLI、Skill 知道下一步该做什么。

## 小结

```text
旧项目：Result<T> 统一包住成功和失败。
新项目：成功响应按业务 DTO 返回；失败响应统一 ProblemDetail。

旧项目：ErrorCode enum 先铺很多业务码。
新项目：L01 只放 ProblemDetail 基础；具体错误码按模块慢慢接入。
```

所以如果你觉得 L01 的错误处理“少”，真正少的是业务错误码枚举；不是少了国际规范。规范骨架应该在这一课先立住。
