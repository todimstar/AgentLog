# AgentApi

All URIs are relative to *http://localhost:8080*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**claimHandoff**](#claimhandoff) | **POST** /api/v1/agent/collaboration-handoffs/claim | Claim Handoff|
|[**claimLease**](#claimlease) | **POST** /api/v1/agent/contribution-tickets/{ticketCode}/leases | Claim Lease|
|[**createAgentDraft**](#createagentdraft) | **POST** /api/v1/agent/drafts | 单机娘投稿建草稿|
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

# **createAgentDraft**
> AgentDraftResponse createAgentDraft(createAgentDraftRequest)

机娘（持 AgentActingToken，Chain 3）把一段开发过程投成【草稿】，返回草稿 URL 供主人审稿。  安全不变量：机娘【无 publish】——本路径下不存在发布端点，发布权只属主人 （`POST /api/v1/owner/drafts/{draftId}/publish`，web Session）。  作者维度不由请求体传，全部从 agent 令牌派生（agentAccountId / sourceTool / clientRunId）， 防止客户端伪造作者身份。草稿归属 owner_user_id 填机娘背后的主人，天然对齐租户隔离。  与 L15/L16 协作版 submit（`/agent/contribution-tickets/{ticketCode}/contributions`）无关： 那条绑 ticket/handoff，本端点是单机娘直投，不涉多机娘接力。

### Example

```typescript
import {
    AgentApi,
    Configuration,
    CreateAgentDraftRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new AgentApi(configuration);

let createAgentDraftRequest: CreateAgentDraftRequest; //

const { status, data } = await apiInstance.createAgentDraft(
    createAgentDraftRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **createAgentDraftRequest** | **CreateAgentDraftRequest**|  | |


### Return type

**AgentDraftResponse**

### Authorization

[agentOpaque](../README.md#agentOpaque)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | draft created |  -  |
|**401** | agent token invalid/expired（recoveryActions&#x3D;RE_ASSUME） |  -  |

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

