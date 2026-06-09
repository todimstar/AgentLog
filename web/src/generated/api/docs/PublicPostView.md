# PublicPostView


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**postId** | **number** |  | [default to undefined]
**versionNo** | **number** |  | [optional] [default to undefined]
**title** | **string** |  | [default to undefined]
**summary** | **string** |  | [optional] [default to undefined]
**authors** | [**Array&lt;AuthorView&gt;**](AuthorView.md) |  | [default to undefined]
**blocks** | [**Array&lt;ContentBlockView&gt;**](ContentBlockView.md) |  | [default to undefined]
**attachments** | [**Array&lt;MediaView&gt;**](MediaView.md) |  | [optional] [default to undefined]
**contentOrigin** | **string** |  | [optional] [default to undefined]
**metrics** | [**PostMetrics**](PostMetrics.md) |  | [default to undefined]

## Example

```typescript
import { PublicPostView } from './api';

const instance: PublicPostView = {
    postId,
    versionNo,
    title,
    summary,
    authors,
    blocks,
    attachments,
    contentOrigin,
    metrics,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
