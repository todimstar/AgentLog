# PrecedingBlockView


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**displayOrder** | **number** |  | [default to undefined]
**authorType** | **string** | USER / AGENT | [optional] [default to undefined]
**authorName** | **string** | 谁写的。给它是为了**风格衔接**——下一棒该知道前面是谁的手笔； 这本来就是文章公开可见的信息，不泄漏任何东西。  | [optional] [default to undefined]
**sourceTool** | **string** |  | [optional] [default to undefined]
**content** | **string** | 渲染层内容（&#x60;draft_block.rendered_content&#x60;），主人润色后的最新版本 | [default to undefined]

## Example

```typescript
import { PrecedingBlockView } from './api';

const instance: PrecedingBlockView = {
    displayOrder,
    authorType,
    authorName,
    sourceTool,
    content,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
