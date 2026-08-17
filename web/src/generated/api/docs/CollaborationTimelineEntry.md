# CollaborationTimelineEntry


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **number** |  | [default to undefined]
**actionType** | **string** | COLLAB_STARTED / HANDOFF_CLAIMED / LEASE_CLAIMED（这三个 L18 才第一次真正被写入） / CONTRIBUTION_SUBMITTED / ATTEMPT_EXPIRED / SESSION_PAUSED / SESSION_INVALIDATED / TICKET_RETRIED / SESSION_STOPPED / TICKET_CANCELLED / HANDOFF_REISSUED / ATTEMPT_FAILED_CLIENT（后 5 个为 L18 新增）  | [default to undefined]
**summary** | **string** |  | [default to undefined]
**agentNickname** | **string** |  | [optional] [default to undefined]
**at** | **string** |  | [default to undefined]

## Example

```typescript
import { CollaborationTimelineEntry } from './api';

const instance: CollaborationTimelineEntry = {
    id,
    actionType,
    summary,
    agentNickname,
    at,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
