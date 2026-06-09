# PublicApi

All URIs are relative to *http://localhost:8080*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**getContributions**](#getcontributions) | **GET** /api/v1/public/posts/{postId}/contributions | 贡献视图|
|[**getPublicPost**](#getpublicpost) | **GET** /api/v1/public/posts/{postId} | 帖子详情|
|[**listChannels**](#listchannels) | **GET** /api/v1/public/channels | 分区列表|
|[**listComments**](#listcomments) | **GET** /api/v1/public/posts/{postId}/comments | 评论树|
|[**listPublicPosts**](#listpublicposts) | **GET** /api/v1/public/posts | Feed|
|[**listTags**](#listtags) | **GET** /api/v1/public/tags | 标签列表|
|[**redirectMedia**](#redirectmedia) | **GET** /api/v1/public/media/{mediaId} | 媒体读取跳转|

# **getContributions**
> ContributionViewResponse getContributions()


### Example

```typescript
import {
    PublicApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new PublicApi(configuration);

let postId: number; // (default to undefined)

const { status, data } = await apiInstance.getContributions(
    postId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **postId** | [**number**] |  | defaults to undefined|


### Return type

**ContributionViewResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | contributions |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **getPublicPost**
> PublicPostView getPublicPost()


### Example

```typescript
import {
    PublicApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new PublicApi(configuration);

let postId: number; // (default to undefined)

const { status, data } = await apiInstance.getPublicPost(
    postId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **postId** | [**number**] |  | defaults to undefined|


### Return type

**PublicPostView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | post |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **listChannels**
> Array<ChannelView> listChannels()


### Example

```typescript
import {
    PublicApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new PublicApi(configuration);

const { status, data } = await apiInstance.listChannels();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**Array<ChannelView>**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | channels |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **listComments**
> CommentPage listComments()


### Example

```typescript
import {
    PublicApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new PublicApi(configuration);

let postId: number; // (default to undefined)
let page: number; // (optional) (default to 0)
let size: number; // (optional) (default to 20)

const { status, data } = await apiInstance.listComments(
    postId,
    page,
    size
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **postId** | [**number**] |  | defaults to undefined|
| **page** | [**number**] |  | (optional) defaults to 0|
| **size** | [**number**] |  | (optional) defaults to 20|


### Return type

**CommentPage**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | comments |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **listPublicPosts**
> PostPage listPublicPosts()


### Example

```typescript
import {
    PublicApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new PublicApi(configuration);

let page: number; // (optional) (default to 0)
let size: number; // (optional) (default to 20)

const { status, data } = await apiInstance.listPublicPosts(
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

**PostPage**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | posts |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **listTags**
> Array<TagView> listTags()


### Example

```typescript
import {
    PublicApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new PublicApi(configuration);

const { status, data } = await apiInstance.listTags();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**Array<TagView>**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | tags |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **redirectMedia**
> redirectMedia()


### Example

```typescript
import {
    PublicApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new PublicApi(configuration);

let mediaId: string; // (default to undefined)

const { status, data } = await apiInstance.redirectMedia(
    mediaId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **mediaId** | [**string**] |  | defaults to undefined|


### Return type

void (empty response body)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: Not defined


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**302** | redirect |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

