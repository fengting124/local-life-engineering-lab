# Agent HITL Immutable Binding and Safe Recovery Design

- Status: Approved for planning
- Type: Security design
- Owners: Project maintainers
- Last verified: 2026-08-01
- Source of truth: `copilot-agent-service/session/`, `copilot-agent-service/api/chat.py`, high-risk MCP tools, and `hitl_approval`
- Baseline: `main@da4a9f4e8699bc189880ddbc7fe6c3f96bcd5741`
- Branch: `fix/agent-hitl-binding-recovery`

## 1. Goal

Make a human approval authorize exactly one immutable high-risk action and make
that action safe to resume after retries, concurrent requests, process restarts,
network timeouts, and checkpoint-write failures.

The protected actions remain:

- `execute_refund`
- `issue_compensation_coupon`

No model capability, route, Prompt, RAG behavior, tool budget, role permission,
or evaluation contract changes are part of this work.

## 2. Current Risk

The current implementation provides useful layers, but they do not form an
end-to-end approval capability:

1. `hitl_approval.action_payload` is mutable JSON without a keyed digest.
2. `checkpoint_id` stores `thread_id`, and resume selects the latest checkpoint.
3. `/chat/resume` reconstructs `pending_action` from the approval row instead of
   proving that the approved payload equals the persisted checkpoint payload.
4. The Python tool node only checks that an `approval_id` exists and matches the
   action name.
5. The Java MCP tools require `approval_id`, but do not verify its status,
   payload, requester identity, merchant scope, or expiry.
6. Concurrent resume requests can both enter the graph before one is observed as
   completed.
7. The Server side-effect ledger prevents duplicate business effects, but it
   does not prove that the effect matches what a human approved.

The required invariant is:

```text
approved payload
  = checkpoint pending action
  = Python execution proposal
  = Java MCP arguments
  = Server side-effect ledger request
```

Any mismatch must fail closed before a business side effect.

## 3. Considered Approaches

### 3.1 Python-only validation

Python could hash the payload, compare it on resume, and continue using
`approval_id` as the Java credential.

This is small, but the enforcement disappears if a caller reaches the MCP tool
without the expected Python path. It also leaves concurrent consumption outside
the database boundary that owns the approval record. Rejected.

### 3.2 Shared approval record with MCP-bound CAS

Python creates and checkpoint-binds an HMAC-protected approval. On execution,
the Java Copilot MCP boundary recomputes the digest, rechecks identity and scope,
and atomically claims the approval before calling the Server. The Server keeps
its existing side-effect ledger.

This uses the existing MySQL database and service boundaries, adds no service,
and puts the final approval enforcement immediately before the side effect.
Selected.

### 3.3 Signed capability validated by LocalLife Server

The Agent could mint a portable signed token and make the Server validate it.
This gives the Server full independence from Copilot, but introduces token
format, key rotation, revocation, and replay semantics across all three
services. It is larger than the current project needs and duplicates the shared
database state machine. Deferred unless Copilot and Server are deployed under
different trust domains.

## 4. Trust Boundaries

- The human approver authorizes a persisted payload, not model output at resume.
- Python Agent owns proposal creation and exact checkpoint binding.
- LocalLife Copilot owns last-mile approval validation and consumption at the
  MCP execution boundary.
- LocalLife Server owns business invariants and side-effect idempotency.
- MySQL is trusted storage. Database-administrator compromise is outside this
  threat model, but accidental or application-level payload mutation is
  detected by HMAC.
- Client-provided identity headers are still assumed to be overwritten or
  signed by a trusted gateway. Replacing the project identity model is out of
  scope.

## 5. Canonical Approval Payload

Only explicit typed fields are signed. Arbitrary dictionary order, display-only
fields, timestamps, and `approval_id` are not part of the business payload.

```json
{
  "payload_version": 1,
  "tool_name": "execute_refund",
  "order_id": "202606100003",
  "amount_minor": 2000,
  "target_user_id": "",
  "merchant_id": "42",
  "requested_user_id": "1001",
  "requested_role": "admin",
  "reason": "订单状态满足退款前置条件，等待人工审批"
}
```

For compensation, `target_user_id` is required and `amount_minor` maps to
`compensation_amount`. Values are normalized once at proposal creation:

- strings are trimmed but not case-folded;
- integer identifiers are encoded as base-10 strings;
- absent optional identity values are empty strings;
- amount is a positive integer in minor units;
- JSON keys use the exact order above, UTF-8, no insignificant whitespace.

Python and Java must share committed test vectors for canonical bytes and the
HMAC-SHA-256 result. A separate `HITL_PAYLOAD_SIGNING_SECRET` is used rather than
reusing the MCP identity-signing key. Development may have an explicit local
default; non-development startup must reject an absent or known default secret.

`order_target_hash` remains an unkeyed SHA-256 hash of the normalized order ID
for lookup and diagnostics. It is not the authorization credential.

## 6. Database Contract

A new Copilot Flyway migration extends `hitl_approval` without rewriting V101.

Required columns:

| Column | Purpose |
| --- | --- |
| `payload_version` | Canonical contract version |
| `payload_digest` | HMAC-SHA-256 of the canonical approval payload |
| `order_target_hash` | Non-reversible order correlation |
| `merchant_id` | Explicit scope, not only nested JSON |
| `requested_user_id` | Original Agent caller |
| `requested_role` | Original caller role |
| `checkpoint_id` | Exact persisted HITL checkpoint; nullable until bound |
| `execution_id` | Current claim identifier |
| `execution_lease_until` | Recovery boundary for interrupted execution |
| `executing_at` | First or latest claim time |
| `executed_at` | Successful completion time |
| `execution_result` | Sanitized successful MCP result for safe replay |
| `execution_error` | Sanitized last execution failure |

The status domain becomes:

```text
PENDING -> APPROVED -> EXECUTING -> EXECUTED
    |
    +-> REJECTED/EXPIRED
```

`PENDING` approvals cannot be approved until `checkpoint_id` and
`payload_digest` are present. Existing rows remain readable but are not
executable until they satisfy the new contract; no legacy approval is silently
grandfathered.

## 7. Checkpoint Binding

`hitl_node` creates the approval with:

- normalized action payload;
- original user, role, and merchant;
- payload digest and version;
- `checkpoint_id = NULL`;
- the same digest and approval ID in `pending_action`.

When LangGraph calls `AsyncMySQLCheckpointer.aput()` after `hitl_node`, the
checkpointer inspects the merged `channel_values`. If it contains a pending HITL
action, the same database transaction that stores the checkpoint conditionally
binds the approval to that exact `checkpoint_id`:

```sql
UPDATE hitl_approval
SET checkpoint_id = :checkpoint_id
WHERE id = :approval_id
  AND thread_id = :thread_id
  AND payload_digest = :payload_digest
  AND checkpoint_id IS NULL
  AND status = 'PENDING';
```

An already bound row is accepted only when all three values are identical.
Mismatch or bind failure makes the checkpoint write fail; the Agent must not
emit an actionable approval request.

If approval creation itself fails, `hitl_node` returns an internal error and
does not expose a fake pending approval with a missing ID.

## 8. Approval and Resume Validation

Approval transition remains a conditional update, with additional predicates:

- status is `PENDING`;
- not expired;
- exact checkpoint is bound;
- digest and payload version are present;
- approver role and merchant scope are authorized.

`/chat/resume` never resumes the latest checkpoint. It loads the exact
`approval.thread_id + approval.checkpoint_id` tuple and verifies:

1. checkpoint `pending_action.approval_id` equals the requested approval;
2. checkpoint action type equals the approval tool;
3. checkpoint payload digest equals the approval digest;
4. recomputing the checkpoint payload HMAC equals the stored digest;
5. checkpoint requester user, role, and merchant equal the approval columns;
6. the current approval status and expiry allow the requested operation;
7. current ToolPolicy and `TOOL_ROLE_MAP` still authorize the original role.

The approver identity is recorded separately and never replaces the original
requester identity used for MCP RBAC. Changing client `thread_id`, requester,
role, merchant, order, amount, user, action, reason, or digest fails closed.

## 9. MCP Execution Claim

Python injects only server-controlled execution metadata into the high-risk
tool call:

- `approval_id`
- `approval_digest`

The Java tool reconstructs the canonical payload from typed MCP arguments and
the signed `RbacContext`, then calls an approval execution guard.

The guard performs, in one transaction:

1. load the approval by ID;
2. check tool, digest, action payload, requester identity, merchant, expiry, and
   status;
3. use a conditional update to claim `APPROVED -> EXECUTING` with a new
   `execution_id` and bounded lease;
4. allow lease recovery only after expiry and only for the identical digest;
5. reject concurrent or mismatched claims before the internal Server call.

After a successful Server response, the guard stores a sanitized result and
transitions `EXECUTING -> EXECUTED` only for the same `execution_id`. Validation
and serialization finish before the claim. Any exception after the claim stays
`EXECUTING` until lease recovery; the implementation does not guess whether an
outbound request reached the Server.

If a retry sees `EXECUTED` with the same digest, the MCP tool returns the stored
result without invoking the Server again. A different digest is always denied.

## 10. Final Side-Effect Idempotency

LocalLife Server continues to use:

```text
(operation_type, approval_id)
```

as the unique side-effect ledger key. This handles the ambiguous case where the
Server committed successfully but Copilot timed out before receiving the
response. After the execution lease expires, a same-digest retry may call the
Server; the ledger replays the first result instead of creating a second refund
or coupon.

No Server business rule is moved into Python or Copilot. Order state, paid
amount, compensation rules, and ledger idempotency remain Server-owned.

## 11. Error Matrix

| Condition | Result | Side effect |
| --- | --- | ---: |
| Missing or invalid signing secret | Service startup fails outside dev | 0 |
| Approval DB write fails | `internal_error`, no actionable HITL event | 0 |
| Checkpoint bind fails | Checkpoint/run fails closed | 0 |
| Missing amount/order binding | Clarification or existing rejection | 0 |
| Expired/rejected approval | Resume denied | 0 |
| User/role/merchant drift | Resume denied and audited | 0 |
| Checkpoint/action/digest drift | Resume denied and audited | 0 |
| Permission changed after approval | ToolPolicy denial | 0 |
| Concurrent resume | One CAS winner; others report in progress | At most 1 |
| Server success, Copilot timeout | Lease recovery plus ledger replay | Exactly 1 |
| Copilot success, Agent timeout | `EXECUTED` result replay | Exactly 1 |
| Checkpoint write fails after execution | `EXECUTED` result replay on resume | Exactly 1 |
| Unknown execution failure | Approval remains non-executable until safe recovery | At most 1 |

Every denial records approval ID, trace ID, reason code, requester identity, and
merchant scope without logging the raw signing secret or full sensitive payload.

## 12. Test Strategy

### Python deterministic tests

- canonical payload and cross-language HMAC vectors;
- creation stores normalized fields and digest;
- approval write failure does not produce pending HITL;
- checkpoint bind succeeds once and is idempotent for the same tuple;
- checkpoint bind rejects changed approval ID, thread, or digest;
- exact-checkpoint resume rejects latest-checkpoint substitution;
- order, amount, user, role, merchant, action, and digest tampering;
- expired, rejected, unbound, and legacy approval denial;
- ToolPolicy and role permission re-evaluation on resume;
- already executed result replay without graph restart.

### Java deterministic and MySQL contract tests

- identical canonical/HMAC vectors;
- real Flyway migration on MySQL Testcontainer;
- exact payload and identity can claim `APPROVED -> EXECUTING`;
- changed order, amount, target user, merchant, role, reason, or digest is denied;
- two concurrent claims produce one winner;
- successful result becomes `EXECUTED` and replays without a second internal
  client call;
- an expired lease can be recovered for the same digest;
- a live lease and different digest cannot be recovered.

### Docker Lite security smoke

- create and reject an approval without high-risk MCP execution;
- approve and resume one isolated refund and one compensation action;
- restart Agent between approval and resume;
- run two concurrent resume requests;
- inject checkpoint payload drift in isolated test data;
- simulate an MCP/Agent timeout and verify Server ledger count remains one;
- verify approval, tool audit, runtime events, and side-effect ledger agree.

The fixed 24x2 DeepSeek quality baseline is not rerun for this security PR.

## 13. Files in Scope

Expected areas:

- `local-life-copilot/src/main/resources/db/migration/`
- `local-life-copilot/src/main/java/.../hitl/` or the nearest existing service
  boundary
- the two Java high-risk tool implementations and their tests
- `copilot-agent-service/session/hitl.py`
- `copilot-agent-service/session/checkpointer.py`
- `copilot-agent-service/api/chat.py`
- the high-risk execution section of `copilot-agent-service/agent/nodes.py`
- settings and Compose wiring for the dedicated signing secret
- focused Python, Java, migration, Docker smoke, and security documentation

## 14. Explicit Non-Goals

- No model, Prompt, LangGraph topology, RAG, Milvus, BM25, or reranker changes.
- No `TOOL_ROLE_MAP` permission expansion or tool-budget increase.
- No EvalCase, fixture, scoring, or 24x2 baseline changes.
- No product-semantics changes for Cases 3, 17, 19, or 49.
- No answer-synthesis work for Cases 16, 18, or 21.
- No replacement of the project IAM or gateway model.
- No SSE duplicate-event cleanup, unrelated Docker policy change, or broad
  `nodes.py`/router refactor.

## 15. Acceptance Criteria

- Every executable approval has a valid payload HMAC and exact checkpoint ID.
- Resume uses the bound checkpoint, never an unqualified latest checkpoint.
- Payload, checkpoint, MCP arguments, identity, and merchant scope all agree.
- Expired, rejected, unbound, tampered, replayed, and unauthorized approvals
  produce zero high-risk side effects.
- Two concurrent resumes produce at most one execution claim.
- Timeout and checkpoint failure recovery produce exactly one refund or coupon.
- Approval, audit, runtime, and side-effect ledger records are traceable.
- Existing Permission and HITL controls do not regress.
- Focused Python and Java tests, full module suites, mutation gates, Docs CI, and
  Docker Lite security smoke pass before the PR becomes Ready.
