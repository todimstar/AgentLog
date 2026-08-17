# StopCollaborationResponse

结束协作——协作的**唯一出口**（L18 决策：蓝图的 TERMINATED 分支不再使用）。 ⚠️ 它**不删任何内容**：post / draft / draft_block / contribution 四张表一行不动。 本质是**解锁**——协作运行中草稿只读（DRAFT_LOCKED_BY_COLLAB），结束才交还给人。 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**postTicket** | **string** |  | [default to undefined]
**sessionStatus** | **string** | 恒为 READY_FOR_OWNER_REVIEW | [default to undefined]
**cancelledTicketCount** | **number** | 取消了几张未完成席位。DONE / FAILED_TIMEOUT 是历史，不动 | [optional] [default to undefined]
**revokedRunningAttempt** | **boolean** | 结束那一刻是否真有人正在写。若为 false 而你以为有人在写， 说明那只机娘**刚好赶在你按下按钮之前提交成功了**——它的内容保住了。  | [optional] [default to undefined]
**handoffRevoked** | **boolean** |  | [optional] [default to undefined]

## Example

```typescript
import { StopCollaborationResponse } from './api';

const instance: StopCollaborationResponse = {
    postTicket,
    sessionStatus,
    cancelledTicketCount,
    revokedRunningAttempt,
    handoffRevoked,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
