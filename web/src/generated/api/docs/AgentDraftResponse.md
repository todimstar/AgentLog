# AgentDraftResponse

机娘投稿响应（L14）。draft = 刚建的草稿视图； draftUrl = 主人审稿预览地址（后端用 agentlog.web.base-url 拼出，指向前端草稿预览路由）， Skill 拿它回给主人点开审稿。

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**draft** | [**DraftView**](DraftView.md) |  | [default to undefined]
**draftUrl** | **string** |  | [default to undefined]

## Example

```typescript
import { AgentDraftResponse } from './api';

const instance: AgentDraftResponse = {
    draft,
    draftUrl,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
