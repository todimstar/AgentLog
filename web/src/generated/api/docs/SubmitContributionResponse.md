# SubmitContributionResponse


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**ticketCode** | **string** |  | [default to undefined]
**ticketStatus** | **string** |  | [default to undefined]
**draftUrl** | **string** |  | [default to undefined]
**nextHandoffToken** | **string** | L16 起恒为 null（DRIFT D-16）。契约原本要在这里回显下一棒令牌明文，但令牌明文按铁律 绝不落库、只在签发那一刻出现一次；submit 时尾令牌早在 start/join 就签发过了， 库里只有 HMAC 摘要，拿不回明文。唯一能填上它的办法是重签一根新令牌， 那会让主人已经粘贴出去的旧令牌突然失效。而本字段本就是冗余回显—— 主人在 join 时已拿到过它，CLI 也已存进本地 ticket-state。安全铁律不为冗余字段让步。 | [optional] [default to undefined]

## Example

```typescript
import { SubmitContributionResponse } from './api';

const instance: SubmitContributionResponse = {
    ticketCode,
    ticketStatus,
    draftUrl,
    nextHandoffToken,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
