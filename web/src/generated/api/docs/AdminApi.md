# AdminApi

All URIs are relative to *http://localhost:8080*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**moderatePost**](#moderatepost) | **POST** /api/v1/admin/moderation/post-versions/{postVersionId} | 治理动作|

# **moderatePost**
> moderatePost(moderatePostRequest)


### Example

```typescript
import {
    AdminApi,
    Configuration,
    ModeratePostRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new AdminApi(configuration);

let postVersionId: number; // (default to undefined)
let moderatePostRequest: ModeratePostRequest; //

const { status, data } = await apiInstance.moderatePost(
    postVersionId,
    moderatePostRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **moderatePostRequest** | **ModeratePostRequest**|  | |
| **postVersionId** | [**number**] |  | defaults to undefined|


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
|**200** | moderated |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

