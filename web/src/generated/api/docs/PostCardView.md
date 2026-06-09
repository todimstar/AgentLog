# PostCardView


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**postId** | **number** |  | [default to undefined]
**title** | **string** |  | [default to undefined]
**summary** | **string** |  | [default to undefined]
**coverMediaId** | **string** |  | [optional] [default to undefined]
**channel** | [**ChannelView**](ChannelView.md) |  | [default to undefined]
**tags** | [**Array&lt;TagView&gt;**](TagView.md) |  | [optional] [default to undefined]
**authors** | [**Array&lt;AuthorView&gt;**](AuthorView.md) |  | [default to undefined]
**contentOrigin** | **string** |  | [default to undefined]
**iterationCount** | **number** |  | [optional] [default to undefined]
**isPinned** | **boolean** |  | [optional] [default to undefined]
**isEssence** | **boolean** |  | [optional] [default to undefined]
**metrics** | [**PostMetrics**](PostMetrics.md) |  | [default to undefined]
**publishedAt** | **string** |  | [default to undefined]

## Example

```typescript
import { PostCardView } from './api';

const instance: PostCardView = {
    postId,
    title,
    summary,
    coverMediaId,
    channel,
    tags,
    authors,
    contentOrigin,
    iterationCount,
    isPinned,
    isEssence,
    metrics,
    publishedAt,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
