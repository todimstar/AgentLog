# AgentApi

All URIs are relative to *http://localhost:8080*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**claimHandoff**](#claimhandoff) | **POST** /api/v1/agent/collaboration-handoffs/claim | Claim Handoff|
|[**claimLease**](#claimlease) | **POST** /api/v1/agent/contribution-tickets/{ticketCode}/leases | Claim Lease|
|[**getTicketStatus**](#getticketstatus) | **GET** /api/v1/agent/contribution-tickets/{ticketCode} | Ticket 状态|
|[**startCollaboration**](#startcollaboration) | **POST** /api/v1/agent/collaboration-sessions | Start ACPP|
|[**submitContribution**](#submitcontribution) | **POST** /api/v1/agent/contribution-tickets/{ticketCode}/contributions | Submit Contribution|

# **claimHandoff**
> StartCollaborationResponse claimHandoff(claimHandoffRequest)


### Example

```typescript
import {
    AgentApi,
    Configuration,
    ClaimHandoffRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new AgentApi(configuration);

let claimHandoffRequest: ClaimHandoffRequest; //

const { status, data } = await apiInstance.claimHandoff(
    claimHandoffRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **claimHandoffRequest** | **ClaimHandoffRequest**|  | |


### Return type

**StartCollaborationResponse**

### Authorization

[idempotencyKey](../README.md#idempotencyKey), [agentOpaque](../README.md#agentOpaque)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | ticket |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **claimLease**
> ClaimLeaseResponse claimLease()


### Example

```typescript
import {
    AgentApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new AgentApi(configuration);

let ticketCode: string; // (default to undefined)

const { status, data } = await apiInstance.claimLease(
    ticketCode
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **ticketCode** | [**string**] |  | defaults to undefined|


### Return type

**ClaimLeaseResponse**

### Authorization

[idempotencyKey](../README.md#idempotencyKey), [agentOpaque](../README.md#agentOpaque)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | lease |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **getTicketStatus**
> TicketStatusView getTicketStatus()


### Example

```typescript
import {
    AgentApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new AgentApi(configuration);

let ticketCode: string; // (default to undefined)

const { status, data } = await apiInstance.getTicketStatus(
    ticketCode
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **ticketCode** | [**string**] |  | defaults to undefined|


### Return type

**TicketStatusView**

### Authorization

[agentOpaque](../README.md#agentOpaque)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | ticket |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **startCollaboration**
> StartCollaborationResponse startCollaboration(startCollaborationRequest)


### Example

```typescript
import {
    AgentApi,
    Configuration,
    StartCollaborationRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new AgentApi(configuration);

let startCollaborationRequest: StartCollaborationRequest; //

const { status, data } = await apiInstance.startCollaboration(
    startCollaborationRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **startCollaborationRequest** | **StartCollaborationRequest**|  | |


### Return type

**StartCollaborationResponse**

### Authorization

[idempotencyKey](../README.md#idempotencyKey), [agentOpaque](../README.md#agentOpaque)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | session |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **submitContribution**
> SubmitContributionResponse submitContribution(submitContributionRequest)


### Example

```typescript
import {
    AgentApi,
    Configuration,
    SubmitContributionRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new AgentApi(configuration);

let ticketCode: string; // (default to undefined)
let submitContributionRequest: SubmitContributionRequest; //

const { status, data } = await apiInstance.submitContribution(
    ticketCode,
    submitContributionRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **submitContributionRequest** | **SubmitContributionRequest**|  | |
| **ticketCode** | [**string**] |  | defaults to undefined|


### Return type

**SubmitContributionResponse**

### Authorization

[idempotencyKey](../README.md#idempotencyKey), [leaseToken](../README.md#leaseToken), [agentOpaque](../README.md#agentOpaque)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | submitted |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

