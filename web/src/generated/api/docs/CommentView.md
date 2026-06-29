# CommentView

单条评论视图。契约最小集(id/author/content/likeCount/createdAt) + 两层树前端组树必需的结构超集字段(L08 落地补齐)。 后端按楼层顺序吐扁平 items，前端按 rootCommentId 分组成两层。 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **number** |  | [default to undefined]
**author** | [**AuthorView**](AuthorView.md) |  | [default to undefined]
**rootCommentId** | **number** | 楼：一级指向自己；二级指向所属一级。前端据此分组成两层。 | [default to undefined]
**parentCommentId** | **number** | 直接父：一级为 null；二级指向被回复的那条。 | [default to undefined]
**replyToCommentId** | **number** | @谁，展示用，不加深层级。 | [default to undefined]
**depth** | **number** | 楼层深度，1&#x3D;一级 / 2&#x3D;二级（永远只有这两个值）。 | [default to undefined]
**content** | **string** |  | [default to undefined]
**status** | **string** | VISIBLE / DELETED。DELETED 时前端渲染\&quot;该评论已删除\&quot;占位。 | [default to undefined]
**likeCount** | **number** |  | [optional] [default to undefined]
**createdAt** | **string** |  | [default to undefined]

## Example

```typescript
import { CommentView } from './api';

const instance: CommentView = {
    id,
    author,
    rootCommentId,
    parentCommentId,
    replyToCommentId,
    depth,
    content,
    status,
    likeCount,
    createdAt,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
