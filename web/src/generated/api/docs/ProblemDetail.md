# ProblemDetail


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**type** | **string** |  | [default to undefined]
**title** | **string** |  | [default to undefined]
**status** | **number** |  | [default to undefined]
**detail** | **string** |  | [default to undefined]
**code** | **string** | 项目自定义错误码（非 HTTP 标准），如 OWNER_TOKEN_EXPIRED / AGENT_NOT_FOUND。前端/CLI 按此分支处理。 | [default to undefined]
**traceId** | **string** |  | [default to undefined]
**recoverable** | **boolean** | 客户端是否可通过某个动作自愈。true 时 recoveryActions 给出具体动作；false（如封禁/非法参数/5xx）时客户端应直接报错。 | [optional] [default to undefined]
**recoveryActions** | **Array&lt;string&gt;** | 自愈动作指南（项目自定义约定，非 OAuth/HTTP 标准）。客户端按数组内的动作常量做 switch 处理。 当前动作字典见 items.enum： PAIR_DEVICE&#x3D;去发起设备配对；RE_PAIR&#x3D;令牌无效，需重新配对； REFRESH_TOKEN&#x3D;access 过期，用 refresh 令牌换新；RE_ASSUME&#x3D;机娘令牌过期/无效，用 owner 令牌重新代入。 | [optional] [default to undefined]

## Example

```typescript
import { ProblemDetail } from './api';

const instance: ProblemDetail = {
    type,
    title,
    status,
    detail,
    code,
    traceId,
    recoverable,
    recoveryActions,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
