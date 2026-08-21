# Compensation Coupon Binding Management API Design

- Status: Approved
- Type: Design
- Owners: Server and product maintainers
- Last verified: 2026-08-21
- Source of truth: Server auth/shop/coupon code, V14, real compensation journey, and Copilot resolver
- Baseline: `main@bf606553631d6986126ebc82136b1cfbbfdee444`
- Target branch: future implementation branch, not this design branch

## 1. Outcome

The existing compensation execution path is real and deterministic, but binding
configuration still requires reviewed SQL. This design proposes an authenticated,
audited Server API for reading, upserting, and disabling
`(shop_id, face_value_minor) -> coupon_template_id` mappings.

The authority model is frozen as a merchant B-side setting:

| Option | Current support | Decision |
| --- | --- | --- |
| Shop-owning approved merchant | Reuses login token, merchant status, and shop ownership | **Approved for v1** |
| Platform operations admin | Server has no trusted admin identity or role | Deferred until a separate trusted Admin IAM exists |
| `X-Internal-Key` caller | Shared service secret has no human identity or shop scope | Rejected |
| Agent/MCP write tool | Makes configuration reachable from model-driven execution | Rejected |

An authenticated user must resolve to an APPROVED merchant, and that merchant
must own the path `shopId`. Only then may the user list, create, replace,
re-enable, or disable that shop's compensation bindings. `merchant_id` is always
derived from the authenticated user and owned shop; it is never accepted from a
request body or header. A future platform-operations capability requires its own
trusted Admin IAM and Server authorization contract. A client-supplied role,
shared internal key, or Agent/MCP mutation does not satisfy that contract.

## 2. Verified Current State

| Boundary | Current fact | Consequence |
| --- | --- | --- |
| Authentication | Bearer token resolves `LoginUserDTO`; it has no role field | No platform-admin authorization exists |
| Merchant authorization | `MerchantService.requireApprovedMerchant()` checks current user | Reusable for B-side ownership |
| Shop isolation | `ShopService.requireOwnShop()` rejects foreign shops | Same fail-closed pattern should be reused |
| Binding identity | V14 has unique `(shop_id, face_value_minor)` | One mapping per shop and face value |
| Template identity | V14 has unique `(shop_id, coupon_template_id)` | One template cannot represent two configured values in one shop |
| Resolver | Admin-only MCP read joins order -> shop -> binding -> template | Management stays outside Agent/MCP |
| Execution | Server rechecks binding, template, amount, status, and signed terms | Config changes safely invalidate stale approvals |
| Template lifecycle | No authenticated template CRUD exists; templates are seeded by SQL | Binding API can only reference an existing template |
| HTTP audit | Server has trace-correlated logs, but no durable config-change audit table | A synchronous audit row is required |
| MCP audit | `tool_audit_log` is asynchronous and specific to MCP calls | It is not the source of truth for this HTTP API |

The API does not create or mutate coupon templates. Automatic template creation,
LLM template selection, and accepting an arbitrary cross-shop template remain
forbidden.

## 3. Resource API

All paths are protected by the existing Bearer-token interceptor and require an
APPROVED merchant who owns `shopId`.

| Method | Path | Semantics |
| --- | --- | --- |
| `GET` | `/api/v1/shops/{shopId}/compensation-coupon-bindings` | List the shop's enabled and disabled mappings |
| `GET` | `/api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValueMinor}` | Read one mapping |
| `PUT` | `/api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValueMinor}` | Create, replace, or re-enable one mapping |
| `PUT` | `/api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValueMinor}/status/disabled` | Idempotently disable without deleting history |

`PUT` follows the repository's existing state-resource style. Hard delete is not
offered because bindings participate in approval and incident history.

### 3.1 Upsert request

```json
{
  "couponTemplateId": "4001"
}
```

The caller does not submit `merchantId`, `enabled`, template terms, or a digest.
The Server derives merchant ownership and reads all template facts itself.

### 3.2 Response

```json
{
  "shopId": "2001",
  "merchantId": "3001",
  "faceValueMinor": 2000,
  "couponTemplateId": "4001",
  "discountType": "CASH",
  "discountValue": 2000,
  "minOrderAmount": 0,
  "validDays": 30,
  "templateStatus": "ACTIVE",
  "remainStock": 19,
  "enabled": true,
  "configurationStatus": "READY",
  "createdAt": "...",
  "updatedAt": "..."
}
```

`remainStock` and `templateStatus` are operational facts, not approved terms.
The management API does not return or persist `coupon_terms_digest`; the resolver
and execution path continue to compute it from canonical current terms.

List/get queries use the binding as the root and left-join template facts. They
must not hide a binding because reviewed SQL or later template changes left it
invalid. `configurationStatus` is one of `READY`, `DISABLED`, `TEMPLATE_MISSING`,
`TEMPLATE_INVALID`, or `MERCHANT_MISMATCH`; template fields may be null only for
`TEMPLATE_MISSING`. A single shop is expected to have few compensation values, so
the first version does not add pagination.

## 4. Validation Contract

Upsert succeeds only when all checks pass:

1. The current user is an APPROVED merchant.
2. The shop exists, is not logically deleted, and belongs to that merchant.
3. `faceValueMinor` is a positive 32-bit integer.
4. The template exists and is not logically deleted.
5. `template.shop_id == path.shopId`.
6. `template.discount_type == CASH`.
7. `template.discount_value == faceValueMinor`.
8. `template.status == ACTIVE` at configuration time.
9. The template is not already bound to another face value in the same shop.

The shop's current DRAFT/ONLINE/OFFLINE/CLOSED status does not change ownership
and is not an additional binding constraint. Compensation for a valid historical
order may still be necessary after a shop changes operating status. The existing
execution path remains the final authority for actual issuance.

Disable requires ownership and an existing mapping. Repeating disable returns the
same disabled representation and creates no second change-audit row.

## 5. Transaction And Concurrency

Configuration traffic is low, so correctness is preferred over a generic upsert
shortcut. Each write transaction:

1. Loads the shop row `FOR UPDATE` and verifies approved-merchant ownership.
2. Loads and validates the template.
3. Checks both V14 unique identities inside the same shop lock.
4. Reads the existing `(shop_id, face_value_minor)` mapping.
5. Inserts or updates the mapping and its `enabled` state.
6. Inserts a durable audit row with before/after snapshots.
7. Commits both writes together.

An identical PUT against an already enabled mapping is a no-op: it returns the
current representation without updating `updated_at` or inserting another audit
row. Re-enabling a disabled row records `ENABLE`; changing the template records
`REPLACE`.

The shop-row lock serializes configuration changes for one shop and avoids MySQL
`ON DUPLICATE KEY UPDATE` ambiguity when either unique key conflicts. Different
shops can still be configured concurrently. A duplicate-key exception is mapped
to a bounded business conflict after re-reading under the lock; it is not exposed
as HTTP 500.

No optimistic version or `If-Match` header is proposed for the first iteration.
Per-shop serialization plus durable before/after audit is sufficient for the
current single-operator product shape. Adding collaborative editing semantics is
a later requirement, not a hidden part of this API.

## 6. Durable Configuration Audit

A future migration should add `compensation_coupon_binding_audit` rather than
reuse asynchronous MCP audit:

```text
id
binding_id
shop_id
merchant_id
face_value_minor
action              CREATE / REPLACE / ENABLE / DISABLE
operator_user_id
request_id
before_snapshot     JSON nullable
after_snapshot      JSON nullable
created_at
```

The audit insert is synchronous and in the same transaction as the binding write.
If it fails, the configuration change rolls back. Snapshots contain only IDs,
face value, enabled state, and stable template terms; they do not contain tokens,
internal keys, approval signatures, or user data. `request_id` comes from the
existing Server MDC context.

Operational logs remain useful for troubleshooting, but logs alone are not an
authoritative change history and may be rotated.

## 7. Error Contract

The implementation should follow the repository's existing HTTP/business-code
convention rather than introduce a new response envelope:

| Condition | HTTP | Code |
| --- | ---: | --- |
| Not logged in / expired token | 401 | Existing auth codes |
| Merchant not approved | 403 | `MERCHANT_NOT_APPROVED` |
| Shop absent or foreign | 403 | `SHOP_FORBIDDEN` to avoid enumeration |
| Binding absent | 400 | `COUPON_COMPENSATION_BINDING_NOT_FOUND` |
| Template absent | 400 | `COUPON_TEMPLATE_NOT_FOUND` |
| Wrong shop/type/value/status | 400 | `COUPON_COMPENSATION_TEMPLATE_INVALID` |
| Unique identity conflict | 400 | `COUPON_COMPENSATION_BINDING_CONFLICT` |
| Invalid path/body value | 400 | `SYS_PARAM_INVALID` |

These codes must be added to `ErrorCode` and the interface reference together.
Database exception text, IDs from foreign shops, and SQL constraint names must not
be returned to clients.

## 8. Approval And Runtime Effects

- A replacement changes the template selected for future proposals.
- Disabling a mapping stops new resolver proposals immediately.
- A pending approval remains cryptographically unchanged, but execution reloads
  the current mapping. Replacement or disable therefore fails closed with the
  existing stale/config rejection and writes no coupon.
- Existing `user_coupon` rows and successful side-effect ledger entries are not
  changed by configuration operations.
- Copilot and Agent require no API, Prompt, RBAC, graph, or tool-budget change.

## 9. Future Implementation Test Matrix

| Layer | Required evidence |
| --- | --- |
| Controller | validation, response envelope, auth status mapping |
| Service | own shop, foreign shop, unapproved merchant, all template checks |
| MySQL Testcontainers | both unique constraints, per-shop concurrent writes, audit atomicity |
| Idempotency | repeated identical PUT and repeated disable do not duplicate rows/audits |
| Cross-service contract | resolver immediately sees create/replace/enable/disable outcomes |
| Safety | no Agent/MCP write path, no client-supplied merchant or template terms |
| Regression | existing real compensation, seckill, Server verify, and Copilot contract tests |
| Docker Lite | configure -> resolve -> create PENDING approval; do not approve in config smoke |

The implementation PR must not run the fixed 24x2 model baseline. This is a
deterministic management-plane feature and should use API, database, and resolver
contract evidence.

## 10. Rollout And Rollback

1. Apply the additive audit-table migration.
2. Deploy the Server management API.
3. Verify read-only list/get against existing bindings.
4. Perform one isolated upsert/disable/re-enable smoke and inspect the audit rows.
5. Verify Copilot resolver behavior for each state.

Rollback disables the API deployment. Existing V14 bindings remain valid; the
additive audit table is retained. Do not roll back by dropping binding or audit
data.

## 11. Explicitly Out Of Scope

- Coupon-template create/update APIs or frontend screens.
- Platform-admin IAM, client-supplied role headers, or shared-key human access.
- Agent/MCP tools that mutate compensation bindings.
- Automatic template creation, ranking, fallback, or LLM selection.
- Approval, HITL, resolver, issuance, stock, or terms-digest changes.
- Notifications, bulk import, multi-shop operations, and policy engines.

## 12. Approved Authority Decision

```text
AUTHORITY MODEL: A
STATUS: APPROVED

authenticated user
    -> APPROVED merchant
    -> owns shopId
    -> may GET / PUT / DISABLE only that shop's compensation bindings
```

This approval permits a separate TDD implementation PR. It does not authorize a
platform-admin identity, template CRUD, frontend work, Agent/MCP mutation tools,
client-supplied `merchant_id` or role claims, or `X-Internal-Key` as a human
management identity.
