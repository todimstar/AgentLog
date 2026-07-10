# WebAuthApi

All URIs are relative to *http://localhost:8080*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**getCsrf**](#getcsrf) | **GET** /api/v1/web/csrf | 获取 CSRF Token|
|[**getCurrentUser**](#getcurrentuser) | **GET** /api/v1/web/me | 当前登录用户|
|[**loginWeb**](#loginweb) | **POST** /api/v1/web/auth/login | 登录|
|[**logoutWeb**](#logoutweb) | **POST** /api/v1/web/auth/logout | 登出|
|[**registerUser**](#registeruser) | **POST** /api/v1/web/auth/register | 注册|
|[**sendRegisterCode**](#sendregistercode) | **POST** /api/v1/web/auth/send-code | 发送注册验证码|
|[**setMyAvatar**](#setmyavatar) | **PUT** /api/v1/web/me/avatar | 设置我的头像|

# **getCsrf**
> CsrfTokenResponse getCsrf()


### Example

```typescript
import {
    WebAuthApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new WebAuthApi(configuration);

const { status, data } = await apiInstance.getCsrf();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**CsrfTokenResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | token |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **getCurrentUser**
> UserView getCurrentUser()


### Example

```typescript
import {
    WebAuthApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new WebAuthApi(configuration);

const { status, data } = await apiInstance.getCurrentUser();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**UserView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | user |  -  |
|**401** | 未登录或 token 无效 |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **loginWeb**
> UserView loginWeb(loginRequest)


### Example

```typescript
import {
    WebAuthApi,
    Configuration,
    LoginRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new WebAuthApi(configuration);

let loginRequest: LoginRequest; //

const { status, data } = await apiInstance.loginWeb(
    loginRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **loginRequest** | **LoginRequest**|  | |


### Return type

**UserView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | user |  -  |
|**401** | 未登录或 token 无效 |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **logoutWeb**
> logoutWeb()


### Example

```typescript
import {
    WebAuthApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new WebAuthApi(configuration);

const { status, data } = await apiInstance.logoutWeb();
```

### Parameters
This endpoint does not have any parameters.


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
|**204** | logout |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **registerUser**
> UserView registerUser(registerRequest)


### Example

```typescript
import {
    WebAuthApi,
    Configuration,
    RegisterRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new WebAuthApi(configuration);

let registerRequest: RegisterRequest; //

const { status, data } = await apiInstance.registerUser(
    registerRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **registerRequest** | **RegisterRequest**|  | |


### Return type

**UserView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | user |  -  |
|**409** | 状态冲突 |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **sendRegisterCode**
> sendRegisterCode(sendCodeRequest)


### Example

```typescript
import {
    WebAuthApi,
    Configuration,
    SendCodeRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new WebAuthApi(configuration);

let sendCodeRequest: SendCodeRequest; //

const { status, data } = await apiInstance.sendRegisterCode(
    sendCodeRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **sendCodeRequest** | **SendCodeRequest**|  | |


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
|**204** | code sent |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **setMyAvatar**
> UserView setMyAvatar(setAvatarRequest)


### Example

```typescript
import {
    WebAuthApi,
    Configuration,
    SetAvatarRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new WebAuthApi(configuration);

let setAvatarRequest: SetAvatarRequest; //

const { status, data } = await apiInstance.setMyAvatar(
    setAvatarRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **setAvatarRequest** | **SetAvatarRequest**|  | |


### Return type

**UserView**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | user |  -  |
|**401** | 未登录或 token 无效 |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

