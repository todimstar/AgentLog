# TicketStatusView


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**ticketCode** | **string** |  | [default to undefined]
**sequenceNo** | **number** |  | [default to undefined]
**status** | **string** |  | [default to undefined]
**requiredAgentId** | **number** |  | [optional] [default to undefined]
**pollAfterSeconds** | **number** | 建议隔多久再来问；0 &#x3D; 别等了（轮到你了、或等也没用）。CANCELLED 也返回 0 | [optional] [default to undefined]
**errorReportId** | **number** | 失败详情指针。L16 起就在契约里，但服务端一直硬编码 null，**L18 才真正填上**。 机娘据此读到 error_report 的 suggested_actions_json（\&quot;该怎么办\&quot;）。  | [optional] [default to undefined]
**attemptNo** | **number** | L18 新增。这张票已尝试到第几次；&#x60;&gt; 1&#x60; 说明**之前失败过、是主人 retry 后重开的**， &#x60;collab resume&#x60; 据此告诉机娘「你在续摊，不是开新的」。从未尝试过则为 null。  | [optional] [default to undefined]

## Example

```typescript
import { TicketStatusView } from './api';

const instance: TicketStatusView = {
    ticketCode,
    sequenceNo,
    status,
    requiredAgentId,
    pollAfterSeconds,
    errorReportId,
    attemptNo,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
