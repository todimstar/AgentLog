# CollaborationTicketDetail


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**ticketCode** | **string** |  | [default to undefined]
**sequenceNo** | **number** |  | [default to undefined]
**status** | **string** | 含 L18 新增的 CANCELLED（主人结束协作时取消未完成席位） | [default to undefined]
**requiredAgentId** | **number** |  | [optional] [default to undefined]
**requiredAgentNickname** | **string** |  | [optional] [default to undefined]
**attempts** | [**Array&lt;CollaborationAttemptDetail&gt;**](CollaborationAttemptDetail.md) | 这张票的每一次尝试。**retry 过的票会有多条**（attempt_no &#x3D; 1 失败、2 重来）， 旧的原样保留——这就是验收栏「错误历史保留」在界面上的样子。  | [default to undefined]

## Example

```typescript
import { CollaborationTicketDetail } from './api';

const instance: CollaborationTicketDetail = {
    ticketCode,
    sequenceNo,
    status,
    requiredAgentId,
    requiredAgentNickname,
    attempts,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
