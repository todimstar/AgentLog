# CollaborationErrorDetail


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **number** |  | [default to undefined]
**ticketCode** | **string** |  | [optional] [default to undefined]
**errorType** | **string** | LEASE_TIMEOUT（Worker 判定）/ CLIENT_REPORTED_FAILURE（L18 机娘自报） | [default to undefined]
**failedStage** | **string** |  | [optional] [default to undefined]
**summary** | **string** |  | [default to undefined]
**suggestedActions** | **Array&lt;string&gt;** | **前端按钮由它驱动，不写死**。服务端按「首棒 / 中间棒」给不同建议： 首棒 &#x60;[\&quot;TERMINATE_SESSION\&quot;]&#x60;（没东西可救）、中间棒 &#x60;[\&quot;RETRY_TICKET\&quot;,\&quot;TERMINATE_SESSION\&quot;]&#x60;。 写死会让「什么时候能 retry」这条规则存在两份，而两份规则一定会漂移。  | [default to undefined]
**createdAt** | **string** |  | [optional] [default to undefined]

## Example

```typescript
import { CollaborationErrorDetail } from './api';

const instance: CollaborationErrorDetail = {
    id,
    ticketCode,
    errorType,
    failedStage,
    summary,
    suggestedActions,
    createdAt,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
