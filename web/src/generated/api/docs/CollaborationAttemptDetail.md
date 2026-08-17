# CollaborationAttemptDetail


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**attemptNo** | **number** |  | [default to undefined]
**status** | **string** | ACTIVE / SUCCEEDED / FAILED_TIMEOUT / FAILED_CLIENT(L18) / REVOKED(L18) | [default to undefined]
**startedAt** | **string** |  | [optional] [default to undefined]
**finishedAt** | **string** |  | [optional] [default to undefined]
**leaseExpiresAt** | **string** |  | [optional] [default to undefined]
**errorReportId** | **number** |  | [optional] [default to undefined]

## Example

```typescript
import { CollaborationAttemptDetail } from './api';

const instance: CollaborationAttemptDetail = {
    attemptNo,
    status,
    startedAt,
    finishedAt,
    leaseExpiresAt,
    errorReportId,
};
```

[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)
