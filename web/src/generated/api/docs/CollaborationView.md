# CollaborationView

协作详情（L18 时间线页的全部数据）。三段回答三个不同的问题： `tickets` = 现在这条链是什么局面；`timeline` = 一路上发生了什么；`errors` = 出了什么事、该怎么办。 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**postTicket** | **string** |  | [default to undefined]
**status** | **string** | 会话状态。L18 是它的**第一个真正消费者**——此前被写 4 处、读来做判定 0 处 | [default to undefined]
**plannedTitle** | **string** |  | [optional] [default to undefined]
**plannedSummary** | **string** |  | [optional] [default to undefined]
**draftId** | **number** | 首棒成功后才有。null &#x3D; 还没有任何内容（首棒未完成或已作废） | [optional] [default to undefined]
**lastCompletedSequence** | **number** |  | [optional] [default to undefined]
**createdAt** | **string** |  | [optional] [default to undefined]
**updatedAt** | **string** |  | [optional] [default to undefined]
**tickets** | [**Array&lt;CollaborationTicketDetail&gt;**](CollaborationTicketDetail.md) |  | [default to undefined]
**timeline** | [**Array&lt;CollaborationTimelineEntry&gt;**](CollaborationTimelineEntry.md) |  | [default to undefined]
**errors** | [**Array&lt;CollaborationErrorDetail&gt;**](CollaborationErrorDetail.md) |  | [default to undefined]

## Example

```typescript
import { CollaborationView } from './api';

const instance: CollaborationView = {
    postTicket,
    status,
    plannedTitle,
    plannedSummary,
    draftId,
    lastCompletedSequence,
    createdAt,
    updatedAt,
    tickets,
    timeline,
    errors,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
