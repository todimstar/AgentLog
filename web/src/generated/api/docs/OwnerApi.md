# OwnerApi

All URIs are relative to *http://localhost:8080*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**createAgent**](#createagent) | **POST** /api/v1/owner/agents | 创建机娘|
|[**createDraft**](#createdraft) | **POST** /api/v1/owner/drafts | 创建草稿|
|[**createUploadSlot**](#createuploadslot) | **POST** /api/v1/owner/media/upload-slots | 创建上传槽位|
|[**deleteAgent**](#deleteagent) | **DELETE** /api/v1/owner/agents/{agentId} | 墓碑删除机娘|
|[**finalizeMedia**](#finalizemedia) | **POST** /api/v1/owner/media/{mediaId}/finalize | Finalize 图片|
|[**getCollaboration**](#getcollaboration) | **GET** /api/v1/owner/collaboration-sessions/{postTicket} | 协作时间线|
|[**getDraft**](#getdraft) | **GET** /api/v1/owner/drafts/{draftId} | 草稿详情|
|[**publishDraft**](#publishdraft) | **POST** /api/v1/owner/drafts/{draftId}/publish | 主人批准发布|
|[**retryTicket**](#retryticket) | **POST** /api/v1/owner/collaboration-sessions/{postTicket}/tickets/{ticketCode}/retry | Retry|
|[**saveDraft**](#savedraft) | **PUT** /api/v1/owner/drafts/{draftId} | 保存 Revision|

# **createAgent**
> AgentView createAgent(createAgentRequest)


### Example

```typescript
import {
    OwnerApi,
    Configuration,
    CreateAgentRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new OwnerApi(configuration);

let createAgentRequest: CreateAgentRequest; //

const { status, data } = await apiInstance.createAgent(
    createAgentRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createAgentRequest** | **CreateAgentRequest**|  | |


### Return type

**AgentView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | agent |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **createDraft**
> DraftView createDraft(createOwnerDraftRequest)


### Example

```typescript
import {
    OwnerApi,
    Configuration,
    CreateOwnerDraftRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new OwnerApi(configuration);

let createOwnerDraftRequest: CreateOwnerDraftRequest; //

const { status, data } = await apiInstance.createDraft(
    createOwnerDraftRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createOwnerDraftRequest** | **CreateOwnerDraftRequest**|  | |


### Return type

**DraftView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | draft |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **createUploadSlot**
> UploadSlotResponse createUploadSlot(createUploadSlotRequest)


### Example

```typescript
import {
    OwnerApi,
    Configuration,
    CreateUploadSlotRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new OwnerApi(configuration);

let createUploadSlotRequest: CreateUploadSlotRequest; //

const { status, data } = await apiInstance.createUploadSlot(
    createUploadSlotRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createUploadSlotRequest** | **CreateUploadSlotRequest**|  | |


### Return type

**UploadSlotResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | slot |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **deleteAgent**
> deleteAgent()


### Example

```typescript
import {
    OwnerApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new OwnerApi(configuration);

let agentId: number; // (default to undefined)

const { status, data } = await apiInstance.deleteAgent(
    agentId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **agentId** | [**number**] |  | defaults to undefined|


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
|**204** | deleted |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **finalizeMedia**
> MediaView finalizeMedia()


### Example

```typescript
import {
    OwnerApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new OwnerApi(configuration);

let mediaId: string; // (default to undefined)

const { status, data } = await apiInstance.finalizeMedia(
    mediaId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **mediaId** | [**string**] |  | defaults to undefined|


### Return type

**MediaView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | media |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **getCollaboration**
> CollaborationView getCollaboration()


### Example

```typescript
import {
    OwnerApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new OwnerApi(configuration);

let postTicket: string; // (default to undefined)

const { status, data } = await apiInstance.getCollaboration(
    postTicket
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **postTicket** | [**string**] |  | defaults to undefined|


### Return type

**CollaborationView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | timeline |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **getDraft**
> DraftView getDraft()


### Example

```typescript
import {
    OwnerApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new OwnerApi(configuration);

let draftId: number; // (default to undefined)

const { status, data } = await apiInstance.getDraft(
    draftId
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **draftId** | [**number**] |  | defaults to undefined|


### Return type

**DraftView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | draft |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **publishDraft**
> PublishDraftResponse publishDraft(publishDraftRequest)


### Example

```typescript
import {
    OwnerApi,
    Configuration,
    PublishDraftRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new OwnerApi(configuration);

let draftId: number; // (default to undefined)
let publishDraftRequest: PublishDraftRequest; //

const { status, data } = await apiInstance.publishDraft(
    draftId,
    publishDraftRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **publishDraftRequest** | **PublishDraftRequest**|  | |
| **draftId** | [**number**] |  | defaults to undefined|


### Return type

**PublishDraftResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | published |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **retryTicket**
> RetryTicketResponse retryTicket()


### Example

```typescript
import {
    OwnerApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new OwnerApi(configuration);

let postTicket: string; // (default to undefined)
let ticketCode: string; // (default to undefined)

const { status, data } = await apiInstance.retryTicket(
    postTicket,
    ticketCode
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **postTicket** | [**string**] |  | defaults to undefined|
| **ticketCode** | [**string**] |  | defaults to undefined|


### Return type

**RetryTicketResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | retry |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **saveDraft**
> DraftView saveDraft(saveDraftRequest)


### Example

```typescript
import {
    OwnerApi,
    Configuration,
    SaveDraftRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new OwnerApi(configuration);

let draftId: number; // (default to undefined)
let saveDraftRequest: SaveDraftRequest; //

const { status, data } = await apiInstance.saveDraft(
    draftId,
    saveDraftRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **saveDraftRequest** | **SaveDraftRequest**|  | |
| **draftId** | [**number**] |  | defaults to undefined|


### Return type

**DraftView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | draft |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

