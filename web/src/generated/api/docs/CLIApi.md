# CLIApi

All URIs are relative to *http://localhost:8080*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**assumeAgent**](#assumeagent) | **POST** /api/v1/cli/agents/{agentAccountId}/assume | Assume 机娘（代入自己名下机娘，换一把短命 AgentActingToken）|
|[**createPairing**](#createpairing) | **POST** /api/v1/cli/device-pairings | CLI 配对|
|[**exchangePairing**](#exchangepairing) | **POST** /api/v1/cli/device-pairings/token | 轮询配对|
|[**listCliAgents**](#listcliagents) | **GET** /api/v1/cli/agents | 列出当前 owner 名下的机娘（供 agents list / assume 挑选）|

# **assumeAgent**
> AssumeAgentResponse assumeAgent(assumeAgentRequest)


### Example

```typescript
import {
    CLIApi,
    Configuration,
    AssumeAgentRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new CLIApi(configuration);

let agentAccountId: number; // (default to undefined)
let assumeAgentRequest: AssumeAgentRequest; //

const { status, data } = await apiInstance.assumeAgent(
    agentAccountId,
    assumeAgentRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **assumeAgentRequest** | **AssumeAgentRequest**|  | |
| **agentAccountId** | [**number**] |  | defaults to undefined|


### Return type

**AssumeAgentResponse**

### Authorization

[ownerOpaque](../README.md#ownerOpaque)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | acting |  -  |

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

# **listCliAgents**
> Array<AgentView> listCliAgents()


### Example

```typescript
import {
    CLIApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new CLIApi(configuration);

const { status, data } = await apiInstance.listCliAgents();
```

### Parameters
This endpoint does not have any parameters.


### Return type

**Array<AgentView>**

### Authorization

[ownerOpaque](../README.md#ownerOpaque)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | agents |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

