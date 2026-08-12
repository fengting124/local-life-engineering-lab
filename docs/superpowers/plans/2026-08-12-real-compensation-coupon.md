# Real Compensation Coupon Implementation Plan

- Status: Active
- Type: Plan
- Owners: Agent, MCP, and Server maintainers
- Last verified: 2026-08-12
- Source of truth: approved real-compensation design, current implementation, and verification output

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the compensation coupon stub with a deterministic, approved, transactional grant of a real `user_coupon`.

**Architecture:** The Agent obtains order evidence, calls an admin-only MCP resolver with the trusted order ID and explicit amount, and binds the returned shop/template/readable terms/digest into HITL payload v2. After approval, Copilot claims the existing execution lease and calls the Server; the Server independently revalidates order, binding, template, and digest before atomically decrementing stock, inserting `user_coupon`, and completing the side-effect ledger.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, Flyway, MySQL 8.4 Testcontainers, Python 3.11, FastAPI, LangGraph 1.2.10, pytest, Docker Compose Lite.

## Global Constraints

- Work only on `feat/real-compensation-coupon`; never commit directly to `main`.
- Preserve the byte-for-byte HITL payload-v1 canonical JSON for refunds.
- Payload-v1 compensation approvals fail closed; compensation uses explicit payload v2.
- `query_order` keeps its current roles; `resolve_compensation_coupon` is admin-only; existing `issue_compensation_coupon` roles are not widened.
- The LLM cannot choose `user_id`, `shop_id`, `merchant_id`, or `coupon_template_id`.
- Do not add automatic template creation, a management API/frontend, policy ranking, notifications, multi-agent behavior, Router/Nodes restructuring, dependencies, or tool-budget increases.
- Do not run the fixed 24x2 DeepSeek baseline.
- Apply V14 only while all `user_coupon` writers are paused; do not claim rolling-version compatibility.
- Every production behavior change follows RED -> GREEN -> focused regression before commit.

---

### Task 1: V14 Coupon Issuance Contract

**Files:**
- Create: `local-life-server/src/main/resources/db/migration/V14__add_compensation_coupon_binding.sql`
- Create: `local-life-server/src/test/java/com/personalprojections/locallife/server/integration/CompensationCouponMigrationIntegrationTest.java`

**Interfaces:**
- Produces `compensation_coupon_binding` with unique `(shop_id, face_value_minor)` and `(shop_id, coupon_template_id)`.
- Produces nullable `user_coupon.seckill_session_id` plus `source_type`, `source_approval_id`, and `issuance_key`.
- Replaces `uk_user_coupon_template` with unique issuance identity while preserving existing seckill semantics.

- [x] **Step 1: Write a failing MySQL 8.4 migration test**

Create a Testcontainers test that applies migrations through V13, inserts one legacy coupon row, applies V14, and asserts:

```text
legacy source_type = SECKILL
legacy issuance_key = SECKILL:{user_id}:{coupon_template_id}
seckill_session_id is nullable
uk_comp_binding_shop_face exists
uk_comp_binding_shop_template exists
uk_user_coupon_issuance exists
uk_user_coupon_source_approval exists
```

Then insert one new seckill row, verify a duplicate user/template seckill issuance key is rejected, and verify two distinct `COMPENSATION:{approval_id}` rows can use the same user/template.

- [x] **Step 2: Run RED**

```bash
mvn -B -pl local-life-server -Dtest=CompensationCouponMigrationIntegrationTest test
```

Expected: FAIL because V14 and its columns/tables do not exist.

- [x] **Step 3: Implement V14 in compatibility-safe SQL order**

The migration must:

```sql
ALTER TABLE user_coupon
  MODIFY seckill_session_id BIGINT UNSIGNED NULL,
  ADD COLUMN source_type VARCHAR(24) NULL,
  ADD COLUMN source_approval_id VARCHAR(64) NULL,
  ADD COLUMN issuance_key VARCHAR(192) NULL;

UPDATE user_coupon
SET source_type = 'SECKILL',
    issuance_key = CONCAT('SECKILL:', user_id, ':', coupon_template_id)
WHERE source_type IS NULL;

ALTER TABLE user_coupon
  MODIFY source_type VARCHAR(24) NOT NULL,
  MODIFY issuance_key VARCHAR(192) NOT NULL,
  DROP INDEX uk_user_coupon_template,
  ADD UNIQUE KEY uk_user_coupon_issuance (issuance_key),
  ADD UNIQUE KEY uk_user_coupon_source_approval (source_approval_id);
```

Create the binding table exactly as approved in the design. No physical foreign keys are introduced because this schema consistently uses logical foreign keys.

- [x] **Step 4: Run GREEN and full migration smoke**

```bash
mvn -B -pl local-life-server -Dtest=CompensationCouponMigrationIntegrationTest test
bash infra/scripts/init-db.sh
bash infra/scripts/init-db.sh
```

Expected: test passes; both migration runs complete safely; V14 is recorded once.

Execution note (2026-08-12): the isolated MySQL 8.4 migration test passed, including
legacy backfill and issuance uniqueness. Applying V14 to the shared development
database is intentionally deferred to Task 8, where every coupon writer can be
stopped and rebuilt inside the required maintenance window.

- [x] **Step 5: Commit**

```bash
git add local-life-server/src/main/resources/db/migration/V14__add_compensation_coupon_binding.sql \
  local-life-server/src/test/java/com/personalprojections/locallife/server/integration/CompensationCouponMigrationIntegrationTest.java
git commit -m "feat(coupon): define compensation issuance schema" \
  -m "Goal: ..." -m "Changes: ..." -m "Verification: ..." -m "Risk: V14 requires the documented coupon-writer maintenance window."
```

### Task 2: Server Domain and Deterministic Terms Contract

**Files:**
- Create: `local-life-server/src/main/java/com/personalprojections/locallife/server/domain/entity/CompensationCouponBinding.java`
- Create: `local-life-server/src/main/java/com/personalprojections/locallife/server/domain/mapper/CompensationCouponBindingMapper.java`
- Create: `local-life-server/src/main/java/com/personalprojections/locallife/server/module/internal/CouponTerms.java`
- Modify: `local-life-server/src/main/java/com/personalprojections/locallife/server/domain/entity/UserCoupon.java`
- Modify: `local-life-server/src/main/java/com/personalprojections/locallife/server/domain/mapper/CouponTemplateMapper.java`
- Modify: `local-life-server/src/main/java/com/personalprojections/locallife/server/module/mq/consumer/SeckillSuccessConsumer.java`
- Test: `local-life-server/src/test/java/com/personalprojections/locallife/server/module/internal/CouponTermsTest.java`
- Test: `local-life-server/src/test/java/com/personalprojections/locallife/server/module/mq/consumer/SeckillSuccessConsumerTest.java`

**Interfaces:**
- `CouponTerms.from(template, shop, merchant)` returns stable terms and lowercase SHA-256.
- `CouponTemplateMapper.decrementActiveStock(long templateId)` returns affected rows.
- `UserCoupon` exposes source fields without changing seckill entity use.

- [x] **Step 1: Write failing digest-vector and mapper-contract tests**

Use this fixed canonical vector in Java and later Python/Copilot tests:

```json
{"terms_version":1,"coupon_template_id":"1001","shop_id":"2001","merchant_id":"3001","discount_type":"CASH","discount_value":2000,"min_order_amount":0,"valid_days":30}
```

Assert exact canonical JSON and a precomputed lowercase SHA-256. Assert mapper SQL contains `remain_stock = remain_stock - 1`, `status = 'ACTIVE'`, and `remain_stock > 0`.

- [x] **Step 2: Run RED**

```bash
mvn -B -pl local-life-server -Dtest=CouponTermsTest test
```

Expected: FAIL because the terms class and atomic mapper method do not exist.

- [x] **Step 3: Add minimal entities and mapper SQL**

The binding mapper provides one exact lookup:

```java
CompensationCouponBinding selectEnabled(long shopId, int faceValueMinor);
```

The stock mapper provides:

```java
int decrementActiveStock(@Param("templateId") long templateId);
```

Do not add generic repositories or a policy service.

Update the existing seckill consumer to populate:

```java
.sourceType("SECKILL")
.sourceApprovalId(null)
.issuanceKey("SECKILL:" + event.getUserId() + ":" + event.getCouponTemplateId())
```

This change must deploy with V14 while all coupon writers are paused. Add a
consumer assertion so the new non-null columns cannot silently regress.

- [x] **Step 4: Run GREEN and focused seckill regressions**

```bash
mvn -B -pl local-life-server \
  -Dtest=CouponTermsTest,SeckillSuccessConsumerTest,CouponServiceTest,SeckillServiceTest test
```

- [x] **Step 5: Commit**

Commit as `feat(coupon): add compensation domain contracts` with Goal / Changes / Verification / Risk body.

### Task 3: Real Server Compensation Transaction

**Files:**
- Modify: `local-life-server/src/main/java/com/personalprojections/locallife/server/module/internal/InternalController.java`
- Modify: `local-life-server/src/main/java/com/personalprojections/locallife/server/module/internal/InternalService.java`
- Modify: `local-life-server/src/test/java/com/personalprojections/locallife/server/module/internal/InternalServiceTest.java`
- Create: `local-life-server/src/test/java/com/personalprojections/locallife/server/integration/CompensationCouponJourneyIntegrationTest.java`

**Interfaces:**
- `CompensateRequest` adds target shop, merchant, template, readable terms, and terms digest.
- `CompensateResult.couponId` is the persisted `user_coupon.id`, not a generated demo string.
- Existing refund request/result remain unchanged.

- [x] **Step 1: Write RED unit tests for validation and ledger race**

Cover order not found, target-user mismatch, shop/merchant mismatch, missing/disabled binding, wrong template, stale terms, inactive/percent/amount mismatch, no stock, successful grant, replay, and duplicate-ledger insert race.

For the race, mock both initial reads as absent, make one ledger insert throw `DuplicateKeyException`, then return an existing `SUCCESS` ledger. Assert replay and zero stock/user-coupon writes for the loser.

- [x] **Step 2: Write RED real-MySQL journey tests**

Use one isolated shop/order/template/binding and assert direct SQL before/after for:

```text
success: inventory -1, user_coupon +1, ledger +1
same approval retry: no further changes, identical coupon ID
two concurrent same-approval calls: one effect
stock zero: rollback and zero coupon
stale terms: zero effect
two approval IDs: two compensation rows may use the same template
```

- [x] **Step 3: Run RED**

```bash
mvn -B -pl local-life-server \
  -Dtest=InternalServiceTest,CompensationCouponJourneyIntegrationTest test
```

- [x] **Step 4: Implement the one-transaction command**

Keep the operation order from the design. Start the ledger only after all read-only validation and immediately before stock mutation. Duplicate ledger insert reloads and replays/in-progress; it never continues.

Construct the coupon as:

```java
UserCoupon.builder()
    .userId(order.getUserId())
    .couponTemplateId(template.getId())
    .seckillSessionId(null)
    .couponStatus("UNUSED")
    .receivedAt(now)
    .expireAt(now.plusDays(template.getValidDays()))
    .sourceType("COMPENSATION")
    .sourceApprovalId(approvalId)
    .issuanceKey("COMPENSATION:" + approvalId)
    .build();
```

- [x] **Step 5: Run GREEN, JaCoCo verify, and commit**

```bash
mvn -B -pl local-life-server \
  -Dtest=InternalServiceTest,CompensationCouponJourneyIntegrationTest test
mvn -B -pl local-life-server clean verify
```

Commit as `feat(server): issue real compensation coupons transactionally` with before/after row evidence in the body.

### Task 4: Admin-Only MCP Resolver

**Files:**
- Create: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/domain/dto/CompensationCouponResolution.java`
- Create: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/domain/mapper/CompensationCouponMapper.java`
- Create: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/tool/impl/ResolveCompensationCouponTool.java`
- Create: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/tool/impl/ResolveCompensationCouponToolTest.java`
- Create: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/domain/mapper/CompensationCouponResolverContractIntegrationTest.java`
- Modify: `copilot-agent-service/agent/tool_router.py`
- Modify: `copilot-agent-service/tests/test_tool_router.py`

**Interfaces:**
- Tool name: `resolve_compensation_coupon`.
- Input: `order_id`, `face_value_minor` only.
- Output: order-derived user/shop/merchant, template ID, readable stable terms, and terms digest.
- Allowed role: `admin` only in both Java tool metadata and Python `TOOL_ROLE_MAP`.

- [ ] **Step 1: Write RED Java tool and real-MySQL contract tests**

Assert the mapper derives all scope fields by joining order -> shop -> binding -> template and rejects missing, disabled, cross-scope, inactive, non-CASH, and amount-mismatched rows.

- [ ] **Step 2: Write RED Python role-contract tests**

Assert admin sees the resolver and CS/merchant do not. Existing role entries must remain byte-for-byte equivalent except for the new tool.

- [ ] **Step 3: Run RED**

```bash
mvn -B -pl local-life-copilot -Dtest=ResolveCompensationCouponToolTest,CompensationCouponResolverContractIntegrationTest test
cd copilot-agent-service && DEBUG=false pytest -q tests/test_tool_router.py
```

- [ ] **Step 4: Implement resolver and role map**

The SQL uses `WHERE o.order_no = ? AND o.deleted=0` and joins the enabled binding by the order's `shop_id` and requested face value. The tool recomputes terms/digest in Java and never trusts stored digest data.

- [ ] **Step 5: Run GREEN and commit**

Commit as `feat(copilot): resolve compensation templates deterministically`.

### Task 5: HITL Payload V2 and Human-Readable Approval

**Files:**
- Modify: `copilot-agent-service/session/hitl_binding.py`
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/tests/test_hitl_binding.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/ApprovalPayload.java`
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/ApprovalPayloadSigner.java`
- Modify: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/hitl/ApprovalPayloadSignerTest.java`
- Modify: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/hitl/HitlApprovalContractIntegrationTest.java`

**Interfaces:**
- Refund payload v1 canonical keys/order remain unchanged.
- Compensation payload v2 requires `shop_id`, `coupon_template_id`,
  `coupon_discount_type`, `coupon_min_order_amount`, `coupon_valid_days`, and
  `coupon_terms_digest`.
- Approval UI/card reads those signed fields from `approval_payload` only.

- [ ] **Step 1: Add shared RED vectors**

Assert Python and Java produce the same exact canonical JSON and HMAC for v1 refund and v2 compensation. Assert v1 compensation, missing v2 fields, invalid type/minimum/validity, and digest mismatch fail closed.

- [ ] **Step 2: Add RED approval-card test**

Assert the pending HITL message displays order, target user, shop, amount, template, minimum spend, and validity from the signed payload.

- [ ] **Step 3: Run RED**

```bash
cd copilot-agent-service && DEBUG=false pytest -q tests/test_hitl_binding.py tests/test_agent_nodes.py
mvn -B -pl local-life-copilot -Dtest=ApprovalPayloadSignerTest,HitlApprovalContractIntegrationTest test
```

- [ ] **Step 4: Implement explicit version branches**

Do not create one optional superset serializer. Use explicit v1 refund and v2 compensation validation/canonicalization so old refund signatures remain unchanged and old compensation cannot execute.

- [ ] **Step 5: Run GREEN and commit**

Commit as `feat(hitl): bind compensation template terms to approval`.

### Task 6: Copilot Execution and Definite Failure State

**Files:**
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/client/LocalLifeInternalClient.java`
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/HitlApprovalMapper.java`
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/hitl/ApprovalExecutionGuard.java`
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/tool/impl/IssueCompensationCouponTool.java`
- Modify: corresponding tests under `local-life-copilot/src/test/java/...`

**Interfaces:**
- `failExecution(claim, sanitizedReason)` CAS transitions only the matching execution to `EXECUTION_FAILED`.
- Definite Server 4xx business rejection calls `failExecution`.
- Timeout/connection/protocol ambiguity leaves `EXECUTING` for lease recovery.

- [ ] **Step 1: Write RED guard/tool tests**

Cover claimed success, replay, in-progress, stale/config/stock business rejection, timeout after possible commit, concurrent completion/failure CAS, and sanitization of secrets.

- [ ] **Step 2: Run RED**

```bash
mvn -B -pl local-life-copilot \
  -Dtest=ApprovalExecutionGuardTest,IssueCompensationCouponToolTest test
```

- [ ] **Step 3: Implement minimal error classification and payload forwarding**

Do not add generic retries. Forward all approved v2 fields to the Server. Classify only an explicit parsed business response as definite; transport and malformed responses remain ambiguous.

- [ ] **Step 4: Run GREEN and full Copilot verify**

```bash
mvn -B -pl local-life-copilot clean verify
```

- [ ] **Step 5: Commit**

Commit as `feat(copilot): execute approved compensation grants safely`.

### Task 7: Deterministic Agent Compensation Route

**Files:**
- Modify: `copilot-agent-service/agent/evidence_gate.py`
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/agent/state.py` only if typed state requires the new evidence fields
- Modify: `copilot-agent-service/tests/test_evidence_gate.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`
- Modify: `copilot-agent-service/evals/eval_contract.py` only if the tool-existence validator must recognize the new production tool; do not alter EvalCase expectations or scoring.

**Interfaces:**
- The controlled route is `query_order -> resolve_compensation_coupon -> HITL proposal` for admin.
- Resolver arguments are reconstructed from route-bound order/amount, never copied from model-supplied scope/template values.
- Resolver rejection terminates without approval or high-risk execution.

- [ ] **Step 1: Write RED route tests**

Cover normal resolution, absent/disabled/conflicting mapping, CS escalation with zero resolver calls, malicious model-supplied user/shop/template ignored, resolver evidence preserved, and no approval until complete v2 evidence exists.

- [ ] **Step 2: Run RED**

```bash
cd copilot-agent-service && DEBUG=false pytest -q \
  tests/test_evidence_gate.py tests/test_agent_nodes.py tests/test_tool_router.py
```

- [ ] **Step 3: Implement the minimal controlled route**

Use existing evidence normalization and stop-reason machinery. Do not add prompt instructions, case-ID branches, extra LLM calls, or a larger budget.

- [ ] **Step 4: Run GREEN, full Agent suite, coverage, and mutation**

```bash
cd copilot-agent-service
DEBUG=false pytest -q
DEBUG=false pytest -q --cov --cov-report=term-missing --cov-fail-under=45
DEBUG=false mutmut run --max-children 4
python scripts/check_mutmut_score.py --min-kill-rate 50 --max-other 0
```

- [ ] **Step 5: Commit**

Commit as `feat(agent): resolve compensation grants before approval`.

### Task 8: Docker Lite Business Journey and Operational Documentation

**Files:**
- Modify: `scripts/business-simulate.sh` only to seed an isolated explicit binding and populate new seckill source fields.
- Create: `scripts/compensation-coupon-smoke.py`
- Modify: `docs/security/HITL审批与安全恢复.md`
- Modify: `docs/04-notes/LocalLifeCopilot项目教程.md`
- Modify: `docs/01-project/05-ER图文档.md`
- Modify: `docs/文档清单.md` if new permanent documentation is added.
- Modify: this plan to record commands/results.

**Interfaces:**
- Smoke creates uniquely prefixed test data and deletes only those rows afterward.
- It directly reconciles `coupon_template`, `user_coupon`, `side_effect_ledger`, `hitl_approval`, and tool audit.

- [ ] **Step 1: Write the smoke script assertions before rebuilding**

The script must fail unless it observes all seven approved journeys: success,
repeat replay, concurrent resume, stock rejection, scope mismatch, ambiguous
response replay, and payload/terms tamper denial.

- [ ] **Step 2: Rebuild current-source Server, Copilot, and Agent images**

```bash
export HITL_PAYLOAD_SIGNING_SECRET='<local test secret>'
docker compose --env-file /absolute/path/to/infra/.env \
  -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml \
  --profile app build locallife-server locallife-copilot copilot-agent
docker compose --env-file /absolute/path/to/infra/.env \
  -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml \
  --profile app up -d locallife-server locallife-copilot copilot-agent
```

- [ ] **Step 3: Execute deterministic and optional model smoke**

```bash
python3 scripts/compensation-coupon-smoke.py
```

Run at most one explicit-amount positive model request and one tampered/invalid
negative request at concurrency one. Do not run 24x2.

- [ ] **Step 4: Run complete gates**

```bash
mvn -B -pl local-life-server clean verify
mvn -B -pl local-life-server test-compile org.pitest:pitest-maven:mutationCoverage
mvn -B -pl local-life-copilot clean verify
cd copilot-agent-service && DEBUG=false pytest -q --cov --cov-fail-under=45
cd .. && python3 scripts/check_docs.py
python3 scripts/check-compose-recovery.py
git diff --check
```

- [ ] **Step 5: Update documentation with measured evidence and commit**

Remove every statement that compensation remains a stub. Record exact row deltas,
image IDs, test counts, mutation results, known lack of notification, and the V14
maintenance-window procedure. Commit as `docs(coupon): document real compensation workflow`.

### Task 9: Final Review and Draft PR

**Files:**
- No new feature files; only correct blocking findings within this plan's scope.

- [ ] **Step 1: Verify branch integrity**

```bash
git fetch --prune origin
git rev-list --left-right --count origin/main...HEAD
git status --short
git diff --check origin/main...HEAD
```

- [ ] **Step 2: Perform an independent diff review**

Focus on migration compatibility, v1 refund signature stability, v1 compensation
denial, order-derived scope, terms display/signature equivalence, ledger insert
race, transaction rollback, error classification, and secret/test-data leakage.

- [ ] **Step 3: Push and create Draft PR**

```bash
git push -u origin feat/real-compensation-coupon
gh pr create --draft --base main --head feat/real-compensation-coupon
```

- [ ] **Step 4: Stop at Draft**

Report final head, commits, deterministic tests, Docker row evidence, optional
model-smoke count, unresolved risks, and `BLOCKING FINDINGS`. Do not mark Ready or
merge without a separate explicit approval.
