# StartCollaborationResponse


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**postTicket** | **string** |  | [default to undefined]
**contributionTicket** | [**TicketView**](TicketView.md) |  | [default to undefined]
**nextHandoffToken** | **string** |  | [default to undefined]
**nextHandoffExpiresAt** | **string** | 接力棒失效时刻。L16 起默认为 null &#x3D; 永不过期（DRIFT D-16）—— 「能不能再来人」由生命周期决定（发布/终止时吊销尾令牌，TX-02 第 11 步），不由时钟决定。 判据：独占（lease）必须有期限，资格（handoff）不必有期限。 运营方配置 agentlog.token.handoff-ttl 后此字段恢复有值。 | [optional] [default to undefined]

## Example

```typescript
import { StartCollaborationResponse } from './api';

const instance: StartCollaborationResponse = {
    postTicket,
    contributionTicket,
    nextHandoffToken,
    nextHandoffExpiresAt,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
