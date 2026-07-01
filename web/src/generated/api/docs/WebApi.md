# WebApi

All URIs are relative to *http://localhost:8080*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**createComment**](#createcomment) | **POST** /api/v1/web/posts/{postId}/comments | 发表评论|
|[**createReport**](#createreport) | **POST** /api/v1/web/reports | 举报内容|
|[**deleteComment**](#deletecomment) | **DELETE** /api/v1/web/comments/{commentId} | 软删评论|
|[**getCollectionState**](#getcollectionstate) | **GET** /api/v1/web/collections/state | 收藏状态|
|[**getReactionState**](#getreactionstate) | **GET** /api/v1/web/reactions/state | 点赞状态|
|[**listNotifications**](#listnotifications) | **GET** /api/v1/web/notifications | 通知列表|
|[**toggleCollection**](#togglecollection) | **POST** /api/v1/web/collections/toggle | 收藏切换|
|[**toggleFollow**](#togglefollow) | **POST** /api/v1/web/follows/toggle | 关注切换|
|[**toggleReaction**](#togglereaction) | **POST** /api/v1/web/reactions/toggle | 点赞切换|

# **createComment**
> CommentView createComment(createCommentRequest)


### Example

```typescript
import {
    WebApi,
    Configuration,
    CreateCommentRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new WebApi(configuration);

let postId: number; // (default to undefined)
let createCommentRequest: CreateCommentRequest; //

const { status, data } = await apiInstance.createComment(
    postId,
    createCommentRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createCommentRequest** | **CreateCommentRequest**|  | |
| **postId** | [**number**] |  | defaults to undefined|


### Return type

**CommentView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | comment |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **createReport**
> createReport(createReportRequest)


### Example

```typescript
import {
    WebApi,
    Configuration,
    CreateReportRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new WebApi(configuration);

let createReportRequest: CreateReportRequest; //

const { status, data } = await apiInstance.createReport(
    createReportRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createReportRequest** | **CreateReportRequest**|  | |


### Return type

void (empty response body)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: Not defined


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | created |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **deleteComment**
> deleteComment()

仅作者本人可删；软删留楼层占位，不物理删。

### Example

```typescript
import {
    WebApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new WebApi(configuration);

let commentId: number; // (default to undefined)

const { status, data } = await apiInstance.deleteComment(
    commentId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **commentId** | [**number**] |  | defaults to undefined|


### Return type

void (empty response body)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**204** | deleted |  -  |
|**403** | 非作者 |  -  |
|**404** | 评论不存在 |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **getCollectionState**
> ToggleStateResponse getCollectionState()


### Example

```typescript
import {
    WebApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new WebApi(configuration);

let postId: number; // (default to undefined)

const { status, data } = await apiInstance.getCollectionState(
    postId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **postId** | [**number**] |  | defaults to undefined|


### Return type

**ToggleStateResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | state |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **getReactionState**
> ToggleStateResponse getReactionState()


### Example

```typescript
import {
    WebApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new WebApi(configuration);

let targetType: 'POST' | 'COMMENT'; // (default to undefined)
let targetId: number; // (default to undefined)

const { status, data } = await apiInstance.getReactionState(
    targetType,
    targetId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **targetType** | [**&#39;POST&#39; | &#39;COMMENT&#39;**]**Array<&#39;POST&#39; &#124; &#39;COMMENT&#39;>** |  | defaults to undefined|
| **targetId** | [**number**] |  | defaults to undefined|


### Return type

**ToggleStateResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | state |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **listNotifications**
> NotificationPage listNotifications()


### Example

```typescript
import {
    WebApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new WebApi(configuration);

let page: number; // (optional) (default to 0)
let size: number; // (optional) (default to 20)

const { status, data } = await apiInstance.listNotifications(
    page,
    size
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **page** | [**number**] |  | (optional) defaults to 0|
| **size** | [**number**] |  | (optional) defaults to 20|


### Return type

**NotificationPage**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | notifications |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **toggleCollection**
> ToggleStateResponse toggleCollection(toggleCollectionRequest)


### Example

```typescript
import {
    WebApi,
    Configuration,
    ToggleCollectionRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new WebApi(configuration);

let toggleCollectionRequest: ToggleCollectionRequest; //

const { status, data } = await apiInstance.toggleCollection(
    toggleCollectionRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **toggleCollectionRequest** | **ToggleCollectionRequest**|  | |


### Return type

**ToggleStateResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | state |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **toggleFollow**
> ToggleStateResponse toggleFollow(toggleFollowRequest)


### Example

```typescript
import {
    WebApi,
    Configuration,
    ToggleFollowRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new WebApi(configuration);

let toggleFollowRequest: ToggleFollowRequest; //

const { status, data } = await apiInstance.toggleFollow(
    toggleFollowRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **toggleFollowRequest** | **ToggleFollowRequest**|  | |


### Return type

**ToggleStateResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | state |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **toggleReaction**
> ToggleStateResponse toggleReaction(toggleReactionRequest)


### Example

```typescript
import {
    WebApi,
    Configuration,
    ToggleReactionRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new WebApi(configuration);

let toggleReactionRequest: ToggleReactionRequest; //

const { status, data } = await apiInstance.toggleReaction(
    toggleReactionRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **toggleReactionRequest** | **ToggleReactionRequest**|  | |


### Return type

**ToggleStateResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | state |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

