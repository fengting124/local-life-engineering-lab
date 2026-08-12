# Real Compensation Coupon Design

- Status: Proposed
- Type: Design
- Owners: Project maintainers
- Last verified: 2026-08-12
- Source of truth: compensation product decision, current coupon/order/HITL code, and Server/Copilot migrations
- Baseline: `main@cbb5df584e4e20487b41770eadf30bb796e4f8a5`
- Target branch: `feat/real-compensation-coupon`

## 1. Goal

Replace the `issue_compensation_coupon` demo result with a real, transactional
coupon grant while preserving the existing HITL payload binding, execution
lease, result replay, RBAC, and side-effect ledger guarantees.

The frozen product rule is:

> A compensation coupon is resolved by the deterministic mapping
> `(shop_id, face_value_minor) -> coupon_template_id`. The LLM cannot choose the
> shop, merchant, target user, or coupon template. The unique template and its
> stable terms must be bound before HITL approval.

This PR does not add automatic template creation, template ranking, user
profiling, multi-template priority, a compensation policy engine, notifications,
or a new Agent architecture.

## 2. Current Facts

The current implementation stops one step before the real business effect:

- `InternalService.issueCompensationCoupon` writes a successful
  `side_effect_ledger` result containing a generated `COMP_*` value, but does not
  decrement template stock or insert `user_coupon`.
- `coupon_template` and `order_info` are shop-scoped. `merchant_id` is derived by
  joining `shop`; one merchant can own multiple shops.
- `user_coupon.seckill_session_id` is currently non-null, so a compensation grant
  cannot honestly reuse the schema without a migration.
- `uk_user_coupon_template(user_id, coupon_template_id)` preserves the seckill
  "one user, one template" rule, but would incorrectly prevent a user from
  receiving the same configured compensation template for two different
  approvals.
- HITL payload version 1 binds order, amount, target user, merchant, requester,
  role, and reason. It does not bind shop, template, or template terms.

## 3. Considered Approaches

### 3.1 Let the LLM choose an existing template

Rejected. A model choice is nondeterministic and would make the approval bind an
amount while leaving the actual benefit undefined.

### 3.2 Create one temporary template for every grant

Rejected. It avoids template ambiguity by polluting template master data, makes
inventory and reporting misleading, and bypasses the administrator's product
configuration.

### 3.3 Explicit shop binding plus deterministic resolver

Accepted. An administrator configures one template for each shop and face value.
A read-only MCP resolver uses the order and requested amount to return the only
valid template. The Server independently repeats all checks inside the grant
transaction.

The resolver remains behind MCP rather than giving the Python Agent direct
database or Server-internal access. It therefore retains the existing identity,
RBAC, audit, and service boundary.

## 4. Data Model

### 4.1 `compensation_coupon_binding`

Server migration `V14` adds:

```sql
CREATE TABLE compensation_coupon_binding (
    id                 BIGINT UNSIGNED NOT NULL,
    shop_id            BIGINT UNSIGNED NOT NULL,
    merchant_id        BIGINT UNSIGNED NOT NULL,
    face_value_minor   INT UNSIGNED NOT NULL,
    coupon_template_id BIGINT UNSIGNED NOT NULL,
    enabled            TINYINT(1) NOT NULL DEFAULT 1,
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                       ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_comp_binding_shop_face (shop_id, face_value_minor),
    UNIQUE KEY uk_comp_binding_shop_template (shop_id, coupon_template_id),
    KEY idx_comp_binding_merchant (merchant_id, enabled)
);
```

The project uses logical foreign keys, so application validation enforces:

```text
binding.shop_id == template.shop_id
binding.merchant_id == shop.merchant_id
binding.face_value_minor == template.discount_value
```

The second unique constraint is intentional for the frozen one-face-value,
one-template rule. Supporting one template for multiple face values would require
a new product contract and migration.

Disabling a mapping updates the existing row. It does not create a second row
with the same business key.

This PR does not add binding-management HTTP endpoints or a frontend. Bindings
are inserted or updated through reviewed migration/operations SQL, and the
business simulator seeds one isolated development binding. The SQL must first
join `coupon_template -> shop` and reject a merchant mismatch. An authenticated
management API is a separate product feature once an operator workflow exists;
direct LLM or public-API writes to this table are forbidden.

### 4.2 `user_coupon` issuance source

The same migration changes `seckill_session_id` to nullable and adds:

```text
source_type        VARCHAR(24) NOT NULL
source_approval_id VARCHAR(64) NULL
issuance_key       VARCHAR(192) NOT NULL
```

Existing rows are backfilled as:

```text
source_type  = SECKILL
issuance_key = SECKILL:{user_id}:{coupon_template_id}
```

New records use:

```text
seckill:      SECKILL:{user_id}:{coupon_template_id}
compensation: COMPENSATION:{approval_id}
```

The migration replaces `uk_user_coupon_template` with:

```text
UNIQUE (issuance_key)
UNIQUE (source_approval_id)
```

MySQL permits multiple null values in the latter index. This preserves the
existing seckill deduplication rule, permits later compensation approvals to
grant the same template to the same user, and guarantees that one approval can
create at most one user coupon even if upper-layer retry behavior changes.

Compensation rows use `seckill_session_id = NULL`, `source_type = COMPENSATION`,
and the approved ID in `source_approval_id`. No fake seckill session is created.

## 5. Deterministic Resolution

Add a read-only MCP tool named `resolve_compensation_coupon`. Its caller-visible
inputs are only:

```json
{
  "order_id": "business order number",
  "face_value_minor": 2000
}
```

The controlled compensation route constructs both arguments from the already
bound route target and the user's explicit amount. It ignores model-provided
`user_id`, `shop_id`, `merchant_id`, and `coupon_template_id` values.

The resolver:

1. reads the order by `order_no`;
2. derives `target_user_id` and `shop_id` from that order;
3. derives `merchant_id` from the order's shop;
4. finds the enabled binding by `(shop_id, face_value_minor)`;
5. reads the referenced template;
6. validates the shop and merchant chain;
7. requires an active `CASH` template whose `discount_value` equals the requested
   face value;
8. returns the resolved IDs, stable terms, and terms digest.

Outcomes are normalized as:

| Condition | Outcome | Further tools |
|---|---|---|
| Binding absent or disabled | `business_rejected/config_missing` | Stop |
| Mapping/template mismatch | `business_rejected/config_conflict` | Stop |
| Template inactive | `business_rejected/template_inactive` | Stop |
| Valid unique mapping | Structured evidence | Continue to HITL |

The resolver does not decrement inventory or reserve a coupon. Availability is
rechecked atomically after approval.

The compensation route becomes:

```text
query_order
  -> deterministic resolve_compensation_coupon
  -> issue_compensation_coupon proposal
  -> HITL
```

The resolver is a read-only CS/admin tool in `TOOL_ROLE_MAP`. `ToolPolicy` remains
the execution authority. No role is widened and no tool budget is increased.

## 6. Terms Digest

The stable canonical object is versioned independently from the HITL payload:

```json
{
  "terms_version": 1,
  "coupon_template_id": "...",
  "shop_id": "...",
  "merchant_id": "...",
  "discount_type": "CASH",
  "discount_value": 2000,
  "min_order_amount": 0,
  "valid_days": 30
}
```

Both Java services serialize these fields in this exact order as compact UTF-8
JSON and compute lowercase SHA-256 hex. Names, descriptions, stock, timestamps,
and display text are intentionally excluded.

`status`, `enabled`, and current stock are execution preconditions rather than
approved benefit terms. Turning a template or mapping off, or exhausting stock,
therefore rejects execution even when the digest is unchanged.

Execution reloads the current binding and template and recomputes the digest.
A mismatch returns `approval_stale` and performs no inventory or coupon write.

## 7. HITL Payload Versioning

Compensation approvals use payload version 2 with these additional required
fields:

```text
shop_id
coupon_template_id
coupon_terms_digest
```

The complete compensation approval identity is:

```text
order_id
target_user_id
shop_id
merchant_id
amount_minor
coupon_template_id
coupon_terms_digest
requested_user_id
requested_role
reason
```

Refunds continue to use the byte-for-byte version 1 canonical contract. Java and
Python signers support both versions explicitly; they never infer a version from
missing fields.

Legacy version 1 compensation approvals are fail-closed after deployment because
they do not identify the approved template. Rollout must list and revoke any
nonterminal legacy compensation approvals before enabling the new image. Existing
version 1 refund approvals remain resumable.

The tool invocation after approval includes all version 2 fields plus
`approval_id` and `approval_digest`. The Copilot execution guard validates the
stored payload and caller before claiming the existing execution lease.

## 8. Server Transaction

The internal compensation command receives the approved order, amount, target
user, shop, merchant, template, terms digest, approval ID, and reason. It does not
trust those values merely because the request came from Copilot.

Inside one Server transaction:

1. Replay an existing successful ledger entry for
   `(issue_compensation_coupon, approval_id)`.
2. Load the order and its shop.
3. Derive the real user, shop, and merchant and compare them with the approved
   values.
4. Reload the enabled binding by `(order.shop_id, amount_minor)` and require the
   approved template ID.
5. Reload the template and validate shop, type, face value, status, and terms
   digest.
6. Insert the `RUNNING` side-effect ledger row with the complete approved request
   snapshot.
7. Atomically decrement stock:

   ```sql
   UPDATE coupon_template
      SET remain_stock = remain_stock - 1
    WHERE id = :template_id
      AND deleted = 0
      AND status = 'ACTIVE'
      AND remain_stock > 0;
   ```

   An affected-row count other than one is `business_rejected/stock_exhausted`.
8. Insert `user_coupon` with `UNUSED`, `received_at = now`,
   `expire_at = now + valid_days`, `seckill_session_id = NULL`, and the
   compensation issuance fields.
9. Complete the ledger with the real `user_coupon.id`, template ID, expiry, amount,
   and `SUCCESS` result snapshot.
10. Commit all three writes together.

Any validation, stock, or insert failure rolls back inventory, user coupon, and
the new ledger row. Tool audit and approval execution error fields remain the
failure evidence.

If Server commits but Copilot loses the response, a later same-approval retry
reads the successful ledger snapshot and returns the original user-coupon ID.
It does not decrement stock or insert again.

### 8.1 Execution failure state

The current execution guard only has a success completion transition. This PR
adds a CAS-protected `failExecution` transition from the matching
`EXECUTING + execution_id` claim to `EXECUTION_FAILED`, clears the lease, and
stores a bounded, sanitized business error.

Only definite business responses use this transition, including stale terms,
disabled configuration, invalid ownership, inactive templates, and exhausted
stock. They require a new approval after the business condition is corrected.

Transport timeout, connection reset, and malformed upstream responses are
ambiguous because the Server may already have committed. They leave the approval
`EXECUTING`; after lease expiry the same approval is reclaimed and the Server
ledger either replays the committed result or performs the first grant. This is
the existing ambiguous-outcome recovery model, not a generic retry loop.

## 9. Failure Matrix

| Failure | Result | Inventory | `user_coupon` | Ledger |
|---|---|---:|---:|---|
| Binding missing/disabled before approval | No approval | 0 | 0 | 0 |
| Mapping or merchant conflict | Fail closed | 0 | 0 | 0 |
| Template terms changed after approval | `approval_stale` | 0 | 0 | 0 |
| Template inactive | `business_rejected` | 0 | 0 | 0 |
| Stock exhausted | `business_rejected` | 0 | 0 | 0 |
| Duplicate/concurrent resume | Replay or in-progress | -1 once | +1 once | +1 once |
| Server commit, response lost | Replay original result | -1 once | +1 once | +1 once |
| Payload or digest tampered | HTTP 409 / denied | 0 | 0 | 0 |

Definite business rejections leave the approval in `EXECUTION_FAILED` with a
sanitized reason. Ambiguous transport failures retain `EXECUTING` until safe
lease recovery.

## 10. Test Strategy

Tests are journey-focused and use real MySQL where transaction and constraints
matter.

### 10.1 Migration and repository contract

- Apply all migrations to an empty MySQL 8.4 Testcontainer.
- Apply `V14` to a schema containing legacy seckill coupons and verify the
  backfilled issuance keys.
- Verify the two binding unique constraints.
- Verify nullable `seckill_session_id`, unique compensation approval, and
  preserved seckill deduplication.

### 10.2 Resolver and payload contract

- Correct shop and amount resolve one active CASH template.
- Missing, disabled, cross-shop, cross-merchant, amount-mismatched, percent, and
  inactive templates fail closed.
- Resolver output derives user/shop/merchant from the order.
- Python and Java generate identical terms and approval digests from shared test
  vectors.
- Payload v1 refund compatibility remains green; v1 compensation is rejected;
  v2 compensation requires all new fields.

### 10.3 Real grant journeys

1. Normal approval: inventory `-1`, `user_coupon +1`, ledger `+1`, real coupon ID
   returned.
2. Same approval resumed twice: all effects remain exactly once and the same
   result is replayed.
3. Two concurrent resumes: only one execution lease and one database grant.
4. No stock: `business_rejected`, no coupon and no dirty decrement.
5. Order, shop, merchant, binding, or template mismatch: fail closed.
6. Server commits but Agent/Copilot sees a timeout: retry returns the stored result
   without another grant.
7. Approval or terms digest tampering: HTTP 409, no coupon, no ledger effect.
8. Template terms change between approval and execution: `approval_stale`, then a
   new approval is required.

The Docker Lite smoke must query `coupon_template`, `user_coupon`,
`side_effect_ledger`, `hitl_approval`, and tool audit rows directly. It uses an
isolated order, user, template, binding, and approval marker, then removes only
those test records. It never deletes a volume.

No 24x2 DeepSeek baseline is run. The product route uses deterministic tests and,
at most, one explicit-amount positive smoke plus one invalid/tampered negative
smoke against the configured model.

## 11. Rollout And Rollback

Rollout order:

1. Pause new compensation approvals.
2. List and revoke nonterminal payload-v1 compensation approvals; do not revoke
   refunds.
3. Apply the additive Server migration and seed explicit test/development
   bindings.
4. Deploy Server, Copilot, then Agent.
5. Run one real compensation journey and database reconciliation.
6. Enable new compensation approvals.

Application rollback keeps the additive binding and source columns. New
compensation approvals are paused before rolling services back. Already issued
user coupons and successful ledger rows are retained as business facts, not
deleted. Version 2 approvals that have not executed are revoked because the old
application cannot validate their full payload.

## 12. Explicit Non-Goals

- No LLM template selection.
- No automatic or per-grant template creation.
- No merchant-wide cross-shop fallback.
- No binding-management API or frontend; this PR uses reviewed operations SQL.
- No complex policy engine, priority, user segmentation, or recommendation.
- No notification system in this PR.
- No Router/Nodes directory refactor, multi-agent graph, RAG change, dependency
  upgrade, tool-budget increase, or permission widening.
- No quality-baseline rerun, capacity test, or observability platform.

## 13. Acceptance Criteria

- A configured, approved compensation creates a real `user_coupon` and returns
  its database ID.
- The order-derived user, shop, and merchant equal the binding and template scope.
- The approved template ID and stable terms digest match execution-time state.
- Inventory decrement, coupon insert, and successful ledger snapshot are atomic.
- One approval produces at most one coupon under retries, concurrency, restart,
  and response loss.
- Missing/conflicting configuration, stale terms, invalid ownership, inactive
  template, no stock, and tampering all fail closed with zero grant.
- Existing refund HITL, seckill issuance, RBAC, and result replay do not regress.
