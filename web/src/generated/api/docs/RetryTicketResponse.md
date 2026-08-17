# RetryTicketResponse

⚠️ **本课删掉了原契约必填的 `attemptNo`（DRIFT D-18）**。 那是蓝图 A 方案的遗留——A 方案里 retry 会真的建出 attempt(status=\'READY\') 行，返回它有意义。 实装选了 B 方案（attempt 由 claim-turn 时才建），那一行**此刻并不存在**： 返回预告值等于为一个已不成立的设计保留没有消费者的字段， 而且叫 attemptNo 会让人以为库里已经有那一行了。 ★ 真正需要 attemptNo 的是**机娘侧**的 `GET /agent/contribution-tickets/{code}`，本课给它加上了。 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**ticketCode** | **string** |  | [default to undefined]
**status** | **string** | 恒为 READY_TO_WRITE（retry 成功即此态） | [default to undefined]
**sequenceNo** | **number** |  | [optional] [default to undefined]
**requiredAgentId** | **number** |  | [default to undefined]
**requiredAgentNickname** | **string** | 页面据此告诉主人「现在去叫谁」。只能是原机娘（APPROVAL_RECORD 冻结） | [optional] [default to undefined]
**unblockedCount** | **number** | 连带解冻了几张后序票（递归 CTE 沿因果链走到底；解冻到 WAITING_PREDECESSOR 而非可写） | [optional] [default to undefined]
**handoffUnfrozen** | **boolean** | 尾令牌是否从 FROZEN 解冻回 AVAILABLE。为 true 时主人多半也需要「重新签发」 | [optional] [default to undefined]

## Example

```typescript
import { RetryTicketResponse } from './api';

const instance: RetryTicketResponse = {
    ticketCode,
    status,
    sequenceNo,
    requiredAgentId,
    requiredAgentNickname,
    unblockedCount,
    handoffUnfrozen,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
