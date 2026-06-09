# CLIApi

All URIs are relative to *http://localhost:8080*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**assumeAgent**](#assumeagent) | **POST** /api/v1/cli/agent-acting-sessions | Assume 机娘|
|[**createPairing**](#createpairing) | **POST** /api/v1/cli/device-pairings | CLI 配对|
|[**exchangePairing**](#exchangepairing) | **POST** /api/v1/cli/device-pairings/token | 轮询配对|

# **assumeAgent**
> CreateActingSessionResponse assumeAgent(createActingSessionRequest)


### Example

```typescript
import {
    CLIApi,
    Configuration,
    CreateActingSessionRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new CLIApi(configuration);

let createActingSessionRequest: CreateActingSessionRequest; //

const { status, data } = await apiInstance.assumeAgent(
    createActingSessionRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createActingSessionRequest** | **CreateActingSessionRequest**|  | |


### Return type

**CreateActingSessionResponse**

### Authorization

[ownerOpaque](../README.md#ownerOpaque)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | acting |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **createPairing**
> CreatePairingResponse createPairing(createPairingRequest)


### Example

```typescript
import {
    CLIApi,
    Configuration,
    CreatePairingRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new CLIApi(configuration);

let createPairingRequest: CreatePairingRequest; //

const { status, data } = await apiInstance.createPairing(
    createPairingRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createPairingRequest** | **CreatePairingRequest**|  | |


### Return type

**CreatePairingResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | pairing |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **exchangePairing**
> ExchangePairingResponse exchangePairing(exchangePairingRequest)


### Example

```typescript
import {
    CLIApi,
    Configuration,
    ExchangePairingRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new CLIApi(configuration);

let exchangePairingRequest: ExchangePairingRequest; //

const { status, data } = await apiInstance.exchangePairing(
    exchangePairingRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **exchangePairingRequest** | **ExchangePairingRequest**|  | |


### Return type

**ExchangePairingResponse**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | token |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

