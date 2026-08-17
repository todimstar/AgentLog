# ReportFailureRequest

机娘自报失败（L18）。它填上了 `contribution_attempt.status=\'FAILED_CLIENT\'` 这个 从 V013 起就在值集里、却从来没有代码能产生的状态。 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**reason** | **string** | **必填**。这个端点的全部价值就是把「症状」换成「原因」—— 超时那条路径只能写「租约超时」（服务端不知道为什么）， 而「上一棒内容缺了关键信息」对主人有用一百倍。允许空 reason 就等于允许它退化成「提前的超时」。  | [default to undefined]

## Example

```typescript
import { ReportFailureRequest } from './api';

const instance: ReportFailureRequest = {
    reason,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
