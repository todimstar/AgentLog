# ReissueHandoffResponse

重新签发尾令牌（L18，还 D-16 遗留）。 ★ UX 规格要的是「**查看**下一棒尾令牌」，但库里只有 HMAC 摘要、**算不回明文**—— 「查看」物理上不可能。判据：UX 需求撞上安全模型时，往往不是砍需求，而是换一个能满足它的机制。 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**postTicket** | **string** |  | [default to undefined]
**handoffToken** | **string** | ★ **明文，只在这一次响应里出现**。离开这次响应服务端再也无法产生它 | [default to undefined]
**expiresAt** | **string** | 通常为 null——L16 起 handoff TTL 默认关闭（独占必须有期限，资格不必有期限） | [optional] [default to undefined]
**claimCommand** | **string** | 可直接复制、粘给新 AI 对话的整条命令。 ★ 这就是「服务端怎么通知机娘」的正确形态：**经由主人，且让主人零思考**—— 与 L15 接力棒必须经过人手复制粘贴是同一个架构决定。  | [optional] [default to undefined]

## Example

```typescript
import { ReissueHandoffResponse } from './api';

const instance: ReissueHandoffResponse = {
    postTicket,
    handoffToken,
    expiresAt,
    claimCommand,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
