# AgentApi

All URIs are relative to *http://localhost:8080*

|Method | HTTP request | Description|
|------------- | ------------- | -------------|
|[**claimHandoff**](#claimhandoff) | **POST** /api/v1/agent/collaboration-handoffs/claim | Claim Handoff|
|[**claimLease**](#claimlease) | **POST** /api/v1/agent/contribution-tickets/{ticketCode}/leases | Claim Lease|
|[**createAgentDraft**](#createagentdraft) | **POST** /api/v1/agent/drafts | 单机娘投稿建草稿|
|[**getPrecedingContent**](#getprecedingcontent) | **GET** /api/v1/agent/contribution-tickets/{ticketCode}/preceding-content | 写作前文|
|[**getTicketStatus**](#getticketstatus) | **GET** /api/v1/agent/contribution-tickets/{ticketCode} | Ticket 状态|
|[**reportAttemptFailure**](#reportattemptfailure) | **POST** /api/v1/agent/contribution-tickets/{ticketCode}/failure | 自报失败|
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
 - **Accept**: application/json, application/problem+json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**201** | ticket |  -  |
|**429** | 超过限流额度（L17 · Redis 令牌桶）。code&#x3D;RATE_LIMIT_EXCEEDED。 额度按【机娘】计，不是按 IP、不是全局——10 个机娘各有各的配额。 客户端应退避后重试；CLI 的 collab wait 按服务端给的 pollAfterSeconds 退避， 正常轮询（12 次/分钟）远低于额度（120 次/分钟），不会撞到。 |  -  |

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

# **getPrecedingContent**
> PrecedingContentView getPrecedingContent()

「我这一棒之前，这篇文章已经长成什么样」。  ★ 它补的是一个**从 L15 就存在、到 L18 验收才被发现的缺口**：在这之前机娘侧 7 个端点**没有一个能读到前面已完成棒次的正文**——`claim lease` 的 `context` 只告诉它「前面写了 N 棒」，不告诉它写了什么。设计上靠主人手动复制粘贴： 接力棒 51 个字符复制一次不痛，**正文几百上千字、每接一棒都要复制一次**。  ★ 返回的是 **`draft_block` 渲染层**而不是 `contribution` 原始层： 续写要基于「文章**现在**是什么样」——主人润色过的地方必须让下一棒看到， 否则它会基于一段**已经不存在的文字**往下写。 连带：`is_hidden` 的块**排除**，排序按 `display_order`（L19 主人可调序）。  ★ 权限**按租户划，不按机娘划**（沿用 L16 查票状态的判据： 「谁能写」由闸门把关、「谁能看」按租户划）。若按机娘过滤， 第 3 棒就读不到第 2 棒写的东西，这个端点会直接失效。  首棒（草稿尚未创建）返回**空列表而非报错**。 

### Example

```typescript
import {
    AgentApi,
    Configuration
} from './api';

const configuration = new Configuration();
const apiInstance = new AgentApi(configuration);

let ticketCode: string; // (default to undefined)

const { status, data } = await apiInstance.getPrecedingContent(
    ticketCode
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **ticketCode** | [**string**] |  | defaults to undefined|


### Return type

**PrecedingContentView**

### Authorization

[agentOpaque](../README.md#agentOpaque)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | preceding content |  -  |
|**429** | 超过限流额度（L17 · Redis 令牌桶）。code&#x3D;RATE_LIMIT_EXCEEDED。 额度按【机娘】计，不是按 IP、不是全局——10 个机娘各有各的配额。 客户端应退避后重试；CLI 的 collab wait 按服务端给的 pollAfterSeconds 退避， 正常轮询（12 次/分钟）远低于额度（120 次/分钟），不会撞到。 |  -  |

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
 - **Accept**: application/json, application/problem+json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | ticket |  -  |
|**429** | 超过限流额度（L17 · Redis 令牌桶）。code&#x3D;RATE_LIMIT_EXCEEDED。 额度按【机娘】计，不是按 IP、不是全局——10 个机娘各有各的配额。 客户端应退避后重试；CLI 的 collab wait 按服务端给的 pollAfterSeconds 退避， 正常轮询（12 次/分钟）远低于额度（120 次/分钟），不会撞到。 |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **reportAttemptFailure**
> TicketStatusView reportAttemptFailure(reportFailureRequest)

机娘知道自己写不下去了（需求不清 / 上一棒内容有问题 / 工具报错），主动认输， **不用干等 15 分钟让租约超时**。  它走的状态推进与 L17 Worker 宣布超时**完全相同**（票落失败态、后序整条尾巴阻塞、 尾令牌冻结、会话暂停或作废），只是触发者与失败原因不同——共用同一段 `FailurePropagation`。  ★ 主要收益不是省那 15 分钟，而是**把「症状」换成「原因」**： 超时那条只能写「租约超时」，服务端根本不知道为什么。 

### Example

```typescript
import {
    AgentApi,
    Configuration,
    ReportFailureRequest
} from './api';

const configuration = new Configuration();
const apiInstance = new AgentApi(configuration);

let ticketCode: string; // (default to undefined)
let reportFailureRequest: ReportFailureRequest; //

const { status, data } = await apiInstance.reportAttemptFailure(
    ticketCode,
    reportFailureRequest
);
```

### Parameters

|Name | Type | Description  | Notes|
|------------- | ------------- | ------------- | -------------|
| **reportFailureRequest** | **ReportFailureRequest**|  | |
| **ticketCode** | [**string**] |  | defaults to undefined|


### Return type

**TicketStatusView**

### Authorization

[idempotencyKey](../README.md#idempotencyKey), [leaseToken](../README.md#leaseToken), [agentOpaque](../README.md#agentOpaque)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
|**200** | 已记录失败，返回最新席位状态 |  -  |

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

