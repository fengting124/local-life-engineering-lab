# Agent HITL Immutable Binding and Safe Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- Status: Complete; Draft PR #30 open
- Type: Plan
- Owners: Project maintainers
- Last verified: 2026-08-04
- Source of truth: `docs/superpowers/specs/2026-08-01-agent-hitl-binding-recovery-design.md`

**Goal:** Bind each approved refund or compensation action to one immutable payload and exact checkpoint, then consume it safely once across retries, concurrency, timeouts, and restarts.

**Architecture:** Python creates an HMAC-protected approval and binds it to the exact LangGraph checkpoint. Java Copilot independently recomputes the same payload digest and atomically claims the approval at the MCP boundary. LocalLife Server keeps business validation and the existing side-effect ledger as the final idempotency layer.

**Tech Stack:** Python 3.10, FastAPI, SQLAlchemy async, LangGraph checkpointing, HMAC-SHA-256, Java 17, Spring Boot 3, MyBatis-Plus, Flyway, MySQL 8.4 Testcontainers, Docker Compose Lite, pytest, JUnit 5.

## Global Constraints

- Work only on `fix/agent-hitl-binding-recovery`, based on `main@da4a9f4e8699bc189880ddbc7fe6c3f96bcd5741`.
- Do not modify the model, Prompt, LangGraph topology, RAG pipeline, EvalCase, fixtures, scoring, tool budgets, or `TOOL_ROLE_MAP`.
- Do not broaden any role permission or let a high-risk tool execute before approval.
- Use `HITL_PAYLOAD_SIGNING_SECRET`; never commit a real secret or print it in logs.
- Resume must load the approval's exact checkpoint ID, never an unqualified latest checkpoint.
- Java Copilot is the last approval enforcement point before the Server call; Server keeps business rules and `side_effect_ledger` idempotency.
- Do not rerun the fixed 24x2 DeepSeek baseline for this security PR.
- Every code task follows red-green-refactor and ends with a detailed Goal/Changes/Verification/Risk commit.

---

## File Map

### New files

- `copilot-agent-service/session/hitl_binding.py`: canonical payload, HMAC signing, constant-time verification, and typed validation errors.
- `copilot-agent-service/tests/test_hitl_binding.py`: Python contract vectors and tamper tests.
- `local-life-copilot/src/main/resources/db/migration/V104__harden_hitl_approval.sql`: approval binding and execution-state columns/indexes.
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/ApprovalPayload.java`: fixed cross-language payload shape.
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/ApprovalPayloadSigner.java`: canonical JSON and HMAC implementation.
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/HitlApprovalRecord.java`: MyBatis projection for approval execution.
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/HitlApprovalMapper.java`: guarded selects and CAS updates.
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/ApprovalExecutionGuard.java`: validate, claim, complete, and replay.
- `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/hitl/ApprovalPayloadSignerTest.java`: shared signing vectors.
- `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/hitl/ApprovalExecutionGuardTest.java`: deterministic guard behavior.
- `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/hitl/HitlApprovalContractIntegrationTest.java`: Flyway/MySQL and concurrent-CAS contract.
- `scripts/hitl-security-smoke.py`: isolated Docker Lite approval/recovery evidence collector.
- `docs/security/HITL审批与安全恢复.md`: operator and incident-response guide.

### Modified files

- `copilot-agent-service/config/settings.py`: dedicated HITL signing configuration.
- `copilot-agent-service/session/hitl.py`: extended model, approval creation, binding, exact-state validation, and status checks.
- `copilot-agent-service/session/checkpointer.py`: transactional checkpoint-to-approval binding.
- `copilot-agent-service/api/chat.py`: exact checkpoint resume, identity/scope recheck, and executed-result replay.
- `copilot-agent-service/api/hitl.py`: approval readiness, expiry, and scope responses.
- `copilot-agent-service/agent/nodes.py`: fail closed on approval persistence failure and inject server-controlled digest.
- Existing Python HITL, chat, node, and checkpoint tests.
- The two Java high-risk tool implementations and tests.
- `local-life-copilot/src/main/resources/application.yml`, Compose files, and env examples.
- Current architecture/tutorial/security documentation and `docs/文档清单.md`.

---

### Task 1: Freeze the Cross-Language Approval Digest Contract

**Files:**
- Create: `copilot-agent-service/session/hitl_binding.py`
- Create: `copilot-agent-service/tests/test_hitl_binding.py`
- Create: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/ApprovalPayload.java`
- Create: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/ApprovalPayloadSigner.java`
- Create: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/hitl/ApprovalPayloadSignerTest.java`
- Modify: `copilot-agent-service/config/settings.py`
- Modify: `local-life-copilot/src/main/resources/application.yml`

**Interfaces:**
- Produces Python `ApprovalPayload`, `canonical_payload_json(payload) -> str`, `sign_payload(payload, secret) -> str`, and `verify_payload_digest(payload, digest, secret) -> bool`.
- Produces Java `ApprovalPayloadSigner.sign(ApprovalPayload)` and `matches(ApprovalPayload, String)`.
- Later tasks must use these APIs and must not independently serialize approval payloads.

- [x] **Step 1: Add failing Python contract-vector tests**

Use a fixed non-production test key and assert one exact canonical string and HMAC:

```python
payload = ApprovalPayload(
    payload_version=1,
    tool_name="execute_refund",
    order_id="202606100003",
    amount_minor=2000,
    target_user_id="",
    merchant_id="42",
    requested_user_id="1001",
    requested_role="admin",
    reason="订单状态满足退款前置条件，等待人工审批",
)
assert canonical_payload_json(payload) == EXPECTED_CANONICAL_JSON
assert sign_payload(payload, "test-only-hitl-key") == EXPECTED_HMAC
```

Also assert key-order independence at construction, trim normalization, changed
order/amount/user/merchant/role/reason mismatch, non-positive amount rejection,
unknown tool rejection, and `hmac.compare_digest` verification.

- [x] **Step 2: Run the Python tests and confirm RED**

Run:

```bash
DEBUG=false PYTHONPATH=copilot-agent-service python -m pytest -q copilot-agent-service/tests/test_hitl_binding.py
```

Expected: collection or import failure because `session.hitl_binding` does not exist.

- [x] **Step 3: Implement the minimal Python contract**

Implement a frozen dataclass with an explicit ordered dictionary:

```python
@dataclass(frozen=True)
class ApprovalPayload:
    payload_version: int
    tool_name: str
    order_id: str
    amount_minor: int
    target_user_id: str
    merchant_id: str
    requested_user_id: str
    requested_role: str
    reason: str

    def canonical_dict(self) -> dict[str, str | int]:
        return {
            "payload_version": self.payload_version,
            "tool_name": self.tool_name,
            "order_id": self.order_id,
            "amount_minor": self.amount_minor,
            "target_user_id": self.target_user_id,
            "merchant_id": self.merchant_id,
            "requested_user_id": self.requested_user_id,
            "requested_role": self.requested_role,
            "reason": self.reason,
        }
```

Serialize with compact UTF-8 JSON and HMAC-SHA-256. Add
`hitl_payload_signing_secret` to settings and reject a missing runtime value.
Tests inject a dedicated non-production value without relying on repository
defaults.

- [x] **Step 4: Run Python tests and confirm GREEN**

Expected: all `test_hitl_binding.py` tests pass.

- [x] **Step 5: Add failing Java tests with the same vector**

The Java test must copy the exact canonical JSON and HMAC produced by the Python
test, then test constant-time match and each mutated field.

- [x] **Step 6: Run the Java test and confirm RED**

```bash
mvn -B -pl local-life-copilot -Dtest=ApprovalPayloadSignerTest test
```

Expected: compilation failure because the HITL signer classes do not exist.

- [x] **Step 7: Implement the minimal Java contract**

Use a Java record for typed fields and an `ObjectMapper` configured only for
compact deterministic output. Build a `LinkedHashMap` in the same key order as
Python, sign UTF-8 bytes with `HmacSHA256`, and compare decoded bytes with
`MessageDigest.isEqual`.

- [x] **Step 8: Run both contract suites and commit**

Expected: Python and Java vectors match exactly.

Commit title:

```text
feat(hitl): define immutable approval payload contract
```

**Verification evidence (2026-08-03):**

- Python RED: `ModuleNotFoundError: session.hitl_binding` before implementation.
- Java RED: compilation failed because `ApprovalPayload` and
  `ApprovalPayloadSigner` did not exist.
- Python focused HITL/checkpoint regression: `143 passed`.
- Java contract vector: `13 passed`.
- Java full clean module regression with MySQL and Testcontainers: `101 passed`.

---

### Task 2: Persist the Binding Contract and Bind the Exact Checkpoint

**Files:**
- Create: `local-life-copilot/src/main/resources/db/migration/V104__harden_hitl_approval.sql`
- Modify: `copilot-agent-service/session/hitl.py`
- Modify: `copilot-agent-service/session/checkpointer.py`
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/tests/test_hitl_service.py`
- Modify: `copilot-agent-service/tests/test_checkpointer.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`

**Interfaces:**
- Produces `HitlService.create_approval(..., approval_payload: ApprovalPayload) -> int`.
- Produces `HitlService.bind_checkpoint(db, approval_id, thread_id, checkpoint_id, payload_digest) -> None`.
- Stores `pending_action.approval_id`, `payload_digest`, and canonical payload in the checkpoint.

- [x] **Step 1: Write failing migration/model tests**

Assert the SQLAlchemy model exposes every V104 column and permits
`checkpoint_id=None` only while status is `PENDING`. Add a migration contract
assertion for indexes on status/lease and digest lookup.

- [x] **Step 2: Write failing approval-creation tests**

Assert creation stores normalized identity columns, `order_target_hash`,
payload version, digest, expiry, and `checkpoint_id=None`. Assert the input dict
is copied and later caller mutation cannot alter stored values.

- [x] **Step 3: Write failing checkpoint binding tests**

Construct a checkpoint with:

```python
"channel_values": {
    "pending_hitl": True,
    "pending_action": {
        "approval_id": 1001,
        "payload_digest": EXPECTED_HMAC,
    },
}
```

Assert `aput()` inserts the checkpoint and conditionally binds the same approval
inside one SQLAlchemy transaction. Same tuple is idempotent; changed thread,
checkpoint, approval ID, or digest raises `HitlBindingError` and rolls back.

- [x] **Step 4: Run focused tests and confirm RED**

```bash
DEBUG=false PYTHONPATH=copilot-agent-service python -m pytest -q \
  copilot-agent-service/tests/test_hitl_service.py \
  copilot-agent-service/tests/test_checkpointer.py \
  copilot-agent-service/tests/test_agent_nodes.py
```

- [x] **Step 5: Add V104 and extend the SQLAlchemy model**

V104 must alter `checkpoint_id` to nullable, add the binding/execution columns,
and preserve existing rows without marking them executable. Use nullable new
columns first; application validation enforces the new contract.

- [x] **Step 6: Implement approval creation and checkpoint binding**

Keep canonicalization in `hitl_binding.py`. `checkpointer.aput()` should extract
only `approval_id` and `payload_digest`, execute the checkpoint insert and
conditional approval update, then commit once.

- [x] **Step 7: Make `hitl_node` fail closed**

If approval persistence fails or returns no ID, return:

```python
{
    "pending_hitl": False,
    "pending_action": None,
    "evidence_stop_reason": "internal_error",
    "stop_reason": "internal_error",
    "final_answer": "审批服务暂时不可用，本次高风险操作未提交。",
}
```

Never emit a pending approval with an empty ID.

- [x] **Step 8: Run focused tests and commit**

Commit title:

```text
feat(hitl): bind approvals to persisted checkpoints
```

**Verification evidence (2026-08-03):**

- RED: focused collection failed because `HitlBindingError` and checkpoint
  binding did not exist.
- Python HITL/API/checkpoint regression: `157 passed`.
- MySQL 8.4 Testcontainers migration run: V101-V104 included in `17`
  validated migrations; contract integration tests `5 passed`.
- Sensitive persistence exception detail is not emitted to logs.

---

### Task 3: Resume Only the Bound Checkpoint and Reauthorize Identity

**Files:**
- Modify: `copilot-agent-service/session/hitl.py`
- Modify: `copilot-agent-service/api/chat.py`
- Modify: `copilot-agent-service/api/hitl.py`
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/tests/test_chat_api.py`
- Modify: `copilot-agent-service/tests/test_hitl_api.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`

**Interfaces:**
- Produces `HitlService.validate_resume(approval, checkpoint_values) -> ApprovalPayload`.
- `/chat/resume` passes `checkpoint_id` in LangGraph config and never rebuilds business arguments from unverified JSON.
- Tool execution receives `approval_id` and `approval_digest` only after validation.

- [x] **Step 1: Add failing exact-checkpoint tests**

Mock two checkpoints under one thread. The older checkpoint is bound to the
approval and the latest contains a changed amount. Assert resume loads the older
exact ID and rejects a missing bound checkpoint instead of using the latest.

- [x] **Step 2: Add failing tamper and identity tests**

Parameterize changes to order, amount, target user, action, reason, payload
digest, original user, original role, and merchant. Each must return a stable
4xx reason code, write a security audit, avoid `agent_graph.astream_events`, and
produce zero MCP calls.

- [x] **Step 3: Add failing lifecycle tests**

Cover unbound legacy approval, expired, rejected, already executing, and already
executed states. `EXECUTED` returns the stored sanitized result without graph
restart; all other invalid states fail closed.

- [x] **Step 4: Run tests and confirm RED**

Run only the named resume/HITL API tests to keep the red signal focused.

- [x] **Step 5: Implement exact-state validation**

Build config as:

```python
config = {
    "configurable": {
        "thread_id": approval.thread_id,
        "checkpoint_id": approval.checkpoint_id,
    }
}
```

Read the state snapshot, recompute the payload HMAC, compare all identity fields,
and re-run `is_tool_allowed_for_role`. Use the original requester identity from
the bound state for MCP; the approver stays audit-only.

- [x] **Step 6: Keep approve/reject CAS semantics**

Approval is allowed only for `PENDING`, unexpired, bound, signed records. Add
merchant scope to the resume header validation without allowing a client to
override the stored merchant.

- [x] **Step 7: Run the entire focused Python HITL baseline and commit**

```bash
DEBUG=false PYTHONPATH=copilot-agent-service python -m pytest -q \
  copilot-agent-service/tests/test_hitl_binding.py \
  copilot-agent-service/tests/test_hitl_service.py \
  copilot-agent-service/tests/test_hitl_api.py \
  copilot-agent-service/tests/test_chat_api.py \
  copilot-agent-service/tests/test_agent_nodes.py \
  copilot-agent-service/tests/test_checkpointer.py
```

Commit title:

```text
fix(hitl): validate bound checkpoint before resume
```

**Verification evidence (2026-08-03):**

- RED: `HitlResumeError` and exact-checkpoint validation were absent.
- Focused HITL/API/checkpoint regression: `184 passed`.
- Complete Agent test suite: `646 passed`.
- Tampered payloads write a stable security audit reason and produce zero
  approval transitions or graph/tool execution.
- Resume config includes the exact persisted `thread_id + checkpoint_id` and
  restores the original requester identity rather than the approver identity.

---

### Task 4: Add the Java Approval Execution Guard and Atomic Claim

**Files:**
- Create: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/HitlApprovalRecord.java`
- Create: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/HitlApprovalMapper.java`
- Create: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/ApprovalExecutionGuard.java`
- Create: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/hitl/ApprovalExecutionGuardTest.java`
- Create: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/hitl/HitlApprovalContractIntegrationTest.java`

**Interfaces:**
- Produces `ExecutionDecision claim(String approvalId, String suppliedDigest, ApprovalPayload payload, RbacContext caller)`.
- Produces `void complete(ExecutionClaim claim, Object result)`.
- Produces replay decisions containing the stored result and no callable execution path.

- [x] **Step 1: Write failing unit tests for all validation fields**

Use an in-memory mocked mapper. Exact approval succeeds; changed tool, order,
amount, target user, reason, role, user, merchant, digest, expiry, or checkpoint
readiness is denied before CAS.

- [x] **Step 2: Write a failing Testcontainers migration test**

Run V101-V104 against MySQL 8.4, insert one `APPROVED` record, and assert V104
columns/indexes exist. Execute two concurrent claims and assert exactly one
conditional update returns 1.

- [x] **Step 3: Run Java tests and confirm RED**

```bash
mvn -B -pl local-life-copilot \
  -Dtest=ApprovalExecutionGuardTest,HitlApprovalContractIntegrationTest test
```

- [x] **Step 4: Implement mapper CAS methods**

Required guarded operations:

```sql
UPDATE hitl_approval
SET status='EXECUTING', execution_id=#{executionId},
    executing_at=NOW(), execution_lease_until=#{leaseUntil}
WHERE id=#{id} AND status='APPROVED'
  AND expire_at>=NOW() AND payload_digest=#{digest};
```

Lease recovery additionally requires `status='EXECUTING'` and
`execution_lease_until < NOW()`. Completion requires the same `execution_id`.

- [x] **Step 5: Implement the guard**

Validate all fields and HMAC before CAS. Generate execution IDs server-side.
Return one of `CLAIMED`, `IN_PROGRESS`, or `REPLAY`. Never return the raw signing
secret or full payload in an exception.

- [x] **Step 6: Test lease recovery and result replay**

Assert a live lease rejects a second claim, an expired lease permits one
same-digest recovery, a different digest never recovers, and `EXECUTED` returns
the stored result.

- [x] **Step 7: Run unit/integration tests and commit**

Commit title:

```text
feat(copilot): atomically consume HITL approvals
```

**Verification evidence (2026-08-03):**

- RED: focused compilation failed before the approval record, mapper, and
  execution guard existed.
- Guard unit and MySQL 8.4 Testcontainers contract suite: `17 passed`.
- Two concurrent claims produced one guarded update with row count `1`, one
  lost-race update with row count `0`, and exactly one persisted execution ID.
- Full clean Copilot module regression: `118 passed`.
- V104 was applied with all `17` Server and Copilot migrations on an empty
  MySQL schema; the status/lease and digest indexes were verified.

---

### Task 5: Enforce the Approval at Both High-Risk MCP Tools

**Files:**
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/tool/impl/ExecuteRefundTool.java`
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/tool/impl/IssueCompensationCouponTool.java`
- Modify: corresponding Java tests
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`

**Interfaces:**
- Both tools require server-injected `approval_id` and `approval_digest`.
- Neither tool calls `LocalLifeInternalClient` unless `ApprovalExecutionGuard` returns `CLAIMED`.
- `REPLAY` returns the stored successful result without a Server call.

- [x] **Step 1: Write failing Java tool tests**

Assert missing digest, mismatched digest, in-progress approval, wrong caller, and
wrong tool never call the internal client. Assert one claimed call completes and
one replay returns the first result with internal-client call count still one.

- [x] **Step 2: Write failing Python injection tests**

Assert the model cannot supply or override `approval_id`/`approval_digest`, and
the tool node injects both values only from the validated `pending_action`.

- [x] **Step 3: Run tests and confirm RED**

Run the two Java tool tests and the named Python high-risk node tests.

- [x] **Step 4: Integrate `ApprovalExecutionGuard`**

Each tool follows:

```java
ExecutionDecision decision = guard.claim(approvalId, approvalDigest, payload, RbacContext.get());
if (decision.isReplay()) return decision.result();
if (!decision.isClaimed()) throw new ToolExecutionDeniedException(decision.reason());
Object result = internalClientCall.get();
guard.complete(decision.claim(), result);
return result;
```

Any exception after claim leaves the approval `EXECUTING`; do not guess that the
Server did not commit.

- [x] **Step 5: Run tests and commit**

Commit title:

```text
fix(copilot): enforce approved payload at MCP boundary
```

**Verification evidence (2026-08-03):**

- RED: Java test compilation failed because both high-risk tools lacked the
  approval guard constructor dependency.
- Java high-risk tool and schema tests: `34 passed`.
- Python tool-node regression: `62 passed`; model-supplied approval ID and
  digest were overwritten by the bound `pending_action` values.
- Full clean Copilot module regression: `128 passed`.
- Missing caller identity, missing digest, denied approval, and live execution
  lease all produced zero internal Server calls; replay returned the sanitized
  stored result without a second call.

---

### Task 6: Prove Concurrent, Timeout, and Checkpoint-Failure Recovery

**Files:**
- Modify: Python HITL/chat/checkpoint tests
- Modify: Java guard integration tests
- Modify: `local-life-server/src/test/java/com/personalprojections/locallife/server/module/internal/InternalServiceTest.java`

**Interfaces:**
- Reuses the approval ID as the Server ledger idempotency key.
- Does not change Server production behavior unless a deterministic test exposes a real ledger defect.

- [x] **Step 1: Add concurrent resume test**

Start two async `/chat/resume` requests for one approval. Assert one reaches the
MCP execution path, the other reports in-progress/already-processed, and the
approval has one execution ID.

- [x] **Step 2: Add Server-success/Copilot-timeout test**

Make the first internal call commit its ledger result and then raise a simulated
transport timeout. Expire the execution lease, retry with the same digest, and
assert the Server returns the original result with one ledger row.

- [x] **Step 3: Add Agent-timeout-after-Copilot-success test**

Complete the Java approval but interrupt Python before the next checkpoint.
Resume again and assert `EXECUTED` result replay without a second MCP/Server call.

- [x] **Step 4: Add restart and exact-checkpoint tests**

Recreate the Agent graph/checkpointer objects between approval and resume. Assert
the exact persisted checkpoint restores and tampered later checkpoints are
ignored.

- [x] **Step 5: Run all focused Python and Java suites**

Expected: zero duplicate side effects and every tamper scenario denied.

- [x] **Step 6: Commit**

Commit title:

```text
test(hitl): cover replay concurrency and crash recovery
```

**Verification evidence (2026-08-03):**

- Two concurrent `/chat/resume` requests both observe the same pending approval,
  but the approval CAS allows exactly one request to enter the Agent graph; the
  race test passed five consecutive runs.
- The real execution race is verified at its ownership boundary with MySQL 8.4,
  `ApprovalExecutionGuard`, and `ExecuteRefundTool`: one request reaches the
  mocked Server, the other receives in-progress, the stored result replays, and
  the Server client is called once.
- Expired-lease recovery changes the execution ID, rejects completion from the
  stale worker, and allows only the recovered owner to persist `EXECUTED`.
- A simulated response loss after Server commit reuses the approval ID as the
  ledger key; retry returns the original refund result with one order mutation
  and one ledger insert/update.
- A recreated Python checkpointer loads the bound checkpoint ID and its pending
  writes without calling the unqualified latest-checkpoint query. Existing
  resume tests reject tampering and replay an `EXECUTED` result without
  restarting the graph.
- Focused Python HITL/chat/checkpoint/node suites: `185 passed`.
- Focused Copilot recovery suites: `21 passed`; full clean Copilot module:
  `131 passed`.
- Focused Server ledger suite: `3 passed`; full clean Server module:
  `183 passed` after restoring the stopped local Redis dependency. The initial
  failure was the external `localhost:6379` connection timeout, not a code
  regression.

---

### Task 7: Wire Docker Lite and Run Real Security Smoke

**Files:**
- Modify: `infra/docker-compose.dev.yml`
- Modify: `infra/docker-compose.lite.yml` if it overrides Agent/Copilot environment
- Modify: `infra/.env.example`
- Modify: `copilot-agent-service/.env.example`
- Create: `scripts/hitl-security-smoke.py`

**Interfaces:**
- Both Agent and Copilot receive the same non-production smoke signing key from environment.
- Smoke data uses unique IDs and cleans only its own approvals/checkpoints/runtime rows.

- [x] **Step 1: Add configuration tests before Compose edits**

Assert settings reject an absent key and tests accept an explicitly injected
non-production key. Validate `docker compose ... config` contains the same
substituted value for Agent and Copilot without printing the value.

- [x] **Step 2: Wire the environment**

Use a required Compose substitution:

```text
HITL_PAYLOAD_SIGNING_SECRET=${HITL_PAYLOAD_SIGNING_SECRET:?set in ignored infra/.env}
```

for Lite and development Compose. Do not store the value in tracked files.

- [x] **Step 3: Rebuild current source images**

```bash
docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml \
  --profile app build locallife-copilot copilot-agent
```

- [x] **Step 4: Start required Lite services and verify migrations**

Confirm V104 applied, both application containers are healthy, and no key value
appears in logs.

- [x] **Step 5: Run isolated real scenarios**

The script must record:

- one rejected approval and zero high-risk tool audits;
- one approved refund with exact digest and one side-effect ledger row;
- one approved compensation with exact digest and one result;
- one Agent restart before resume;
- two concurrent resumes with one claim;
- one tampered checkpoint payload with zero execution;
- one simulated timeout/retry with one business side effect;
- approval/tool audit/runtime/ledger correlation IDs.

- [x] **Step 6: Save a sanitized report**

Write ignored raw evidence under `artifacts/security/hitl-<timestamp>/` and commit
only aggregate evidence to the security document. Do not commit database dumps,
keys, full payloads, or user data.

- [x] **Step 7: Commit**

Commit title:

```text
test(docker): verify HITL recovery security in Lite
```

**Completed evidence (2026-08-04):**

- Compose rejects an absent signing key and injects one runtime value into both
  Agent and Copilot; tracked environment examples contain placeholders only.
- Current-source `copilot-agent:latest` and `locallife-copilot:latest` images
  rebuilt successfully. The Copilot build recovered from a Maven Central TLS
  handshake failure on its existing second retry.
- MySQL, Redis, Server, Copilot, and Agent were healthy; `copilot:V104` was
  recorded with 12 contract columns, two indexes, and nullable checkpoint ID.
- The ignored raw report `artifacts/security/hitl-20260804-195601/report.json`
  recorded `PASS` for seven isolated scenarios: rejection, refund,
  compensation, Agent restart replay, concurrent claim, tampered real
  checkpoint, and ambiguous Server-commit/Copilot-retry.
- Tampering a real serializer-produced checkpoint returned HTTP 409 with
  `payload_mismatch`; approval remained `PENDING` with no execution or lease,
  tool audit and side-effect ledger counts stayed zero, and the order remained
  `PAID`.
- Approved tool audit rows shared the runtime trace ID and stored
  `approval_digest` as `[REDACTED]`; the signing key and protected tamper value
  were absent from inspected logs.
- The script's isolated session, approval, checkpoint, audit, ledger, and order
  rows were all removed; existing volumes and containers were preserved.
- Real graph probing found and fixed an intermediate unbound-HITL checkpoint
  transition (`7276150`), and runtime audit review fixed async trace propagation
  and credential redaction (`8c780c5`). Neither fix changes RBAC, tool budgets,
  model prompts, or high-risk execution authorization.

---

### Task 8: Documentation, Full Gates, Review, and Draft PR

**Files:**
- Create: `docs/security/HITL审批与安全恢复.md`
- Modify: `docs/04-notes/LocalLifeCopilot项目教程.md`
- Modify: `docs/01-project/07-Copilot企业级Agent设计.md`
- Modify: `docs/04-notes/测试总览与结果汇总.md`
- Modify: `docs/文档清单.md`
- Modify: this plan status and evidence checkboxes

**Interfaces:**
- Documentation distinguishes implemented guarantees from remaining ingress/IAM risks.
- No model-quality improvement is claimed and no 24x2 result is changed.

- [x] **Step 1: Write the operator document**

Include status machine, payload fields, exact checkpoint flow, CAS SQL,
timeout/restart recovery, audit queries, alert conditions, secret rotation, and a
production incident checklist.

- [x] **Step 2: Synchronize architecture/tutorial facts**

Replace outdated claims that `checkpoint_id` equals `thread_id` or that
`approval_id` alone is sufficient. Add code references and interview questions
about HMAC versus SHA-256, CAS versus application locks, at-most-once claims
versus exactly-once business effects, and ambiguous network outcomes.

- [x] **Step 3: Run full deterministic quality gates**

```bash
DEBUG=false PYTHONPATH=copilot-agent-service python -m pytest -q copilot-agent-service/tests
mvn -B -pl local-life-copilot clean verify
mvn -B -pl local-life-server clean verify
python3 scripts/check_docs.py
git diff --check
```

Run the existing Agent and Java mutation gates without lowering thresholds.

- [x] **Step 4: Perform an independent security diff review**

Review migration compatibility, canonical vector equality, constant-time digest
comparison, exact checkpoint selection, every status transition, CAS row counts,
lease recovery, result sanitization, log redaction, and absence of permission or
budget changes. Record blocking findings with file and line references.

- [x] **Step 5: Update design and plan status**

Mark the design Implemented only when deterministic tests, Docker smoke, and
review all pass. Record actual counts and unresolved risks; do not write planned
claims as completed facts.

Final evidence on 2026-08-04: Agent `655 passed`; Copilot `134 tests`;
Server `183` unit tests plus `5` integration tests with JaCoCo; Agent mutation
`826/1180` killed with `other=0`; Server PIT `87/136` killed; Docker Lite HITL
security smoke `7/7 PASS`. The final independent diff review reported
`BLOCKING FINDINGS=0` after UTC expiry, atomic expiration, expired-lease smoke,
and audit error-text redaction fixes.

Residual risks remain explicit: production clocks require NTP synchronization;
the lease-recovery smoke injects the expired execution state through SQL instead
of a real network partition; text-pattern redaction cannot recognize every
unlabelled credential; ingress identity, audit fail-open behavior, key rotation,
official LangGraph interrupt migration, and real compensation issuance remain
separate work.

- [x] **Step 6: Commit final documentation**

Commit title:

```text
docs(security): record HITL recovery guarantees
```

- [x] **Step 7: Push and create a Draft PR to `main`**

The PR body must list commits, migration impact, red-green evidence, Docker
evidence, security invariants, known limits, and rollback considerations. Keep
it Draft until all required CI is green and an independent review reports zero
blocking findings. Do not merge without explicit user confirmation.

Branch `fix/agent-hitl-binding-recovery` was pushed and opened as Draft PR
[#30](https://github.com/fengting124/local-life-engineering-lab/pull/30).
