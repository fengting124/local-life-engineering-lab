# Compensation Coupon Binding Management API Implementation Plan

- Status: Active
- Type: Plan
- Owners: Server maintainers
- Last verified: 2026-08-21
- Source of truth: approved binding-management design, Server auth/shop/coupon code, V14, and deterministic tests

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an authenticated APPROVED merchant safely list, create, replace, re-enable, and disable compensation coupon bindings only for shops it owns, with durable same-transaction audit history.

**Architecture:** Add a shop-scoped REST management surface in `local-life-server`. The service derives merchant identity through `MerchantService`, locks the owned shop row, validates an existing same-shop ACTIVE CASH template, updates the V14 binding, and writes a V15 before/after audit row in one transaction. Reads use the binding as the root so disabled or historically invalid configuration remains visible; Agent, MCP, HITL, resolver, and issuance code remain unchanged.

**Tech Stack:** Java 17, Spring Boot MVC, MyBatis-Plus, MySQL 8.4, Jakarta Validation, JUnit 5, Mockito, Testcontainers, Maven, Docker Compose Lite.

## Global Constraints

- Authority is exactly `authenticated user -> APPROVED merchant -> owns shopId`.
- The request never accepts `merchant_id`, role/admin claims, template terms, or internal keys.
- No platform Admin IAM, template CRUD, frontend, Agent/MCP write tools, Prompt, HITL, resolver, or issuance changes.
- Template must exist, belong to the path shop, be `CASH`, match `faceValueMinor`, be `ACTIVE`, and not map to another face value in the shop.
- Writes serialize on the shop row with `FOR UPDATE`; binding and audit commit or roll back together.
- Identical active PUT and repeated disable are idempotent no-ops without extra audit rows.
- Use the existing response envelope and HTTP/business-code convention; do not add dependencies.
- Follow strict RED -> GREEN -> REFACTOR for each production behavior.
- Do not run the fixed 24x2 DeepSeek baseline.

---

## File Map

**Create**

- `local-life-server/src/main/resources/db/migration/V15__add_compensation_coupon_binding_audit.sql`: durable configuration audit schema.
- `local-life-server/src/main/java/com/personalprojections/locallife/server/domain/entity/CompensationCouponBindingAudit.java`: MyBatis audit row.
- `local-life-server/src/main/java/com/personalprojections/locallife/server/domain/mapper/CompensationCouponBindingAuditMapper.java`: append-only audit mapper.
- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/coupon/dto/CompensationCouponBindingRequest.java`: request with only `couponTemplateId`.
- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/coupon/dto/CompensationCouponBindingVO.java`: management representation and configuration status.
- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/coupon/service/CompensationCouponBindingService.java`: authorization, validation, locking, state changes, views, and audit transaction.
- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/coupon/controller/CompensationCouponBindingController.java`: four approved REST operations.
- Migration, service, persistence, and controller tests under matching `local-life-server/src/test/java` packages.

**Modify**

- `CompensationCouponBindingMapper.java`: list, exact lookup, and template-conflict lookup.
- `ShopMapper.java`: `selectByIdForUpdate(long shopId)`.
- `ErrorCode.java`: four approved coupon configuration errors.
- `AuthInterceptorTest.java`: prove all nested management endpoints require Bearer auth.
- `docs/01-project/10-接口规范文档.md`, `docs/文档清单.md`, and the approved design: deterministic contract and evidence only.

---

### Task 1: V15 Audit Persistence Contract

**Interfaces**

- Produces `compensation_coupon_binding_audit` with actions `CREATE`, `REPLACE`, `ENABLE`, `DISABLE`.
- Produces `CompensationCouponBindingAuditMapper.insert(...)` for the service transaction.

- [x] **Step 1: Write the failing MySQL migration test**

Apply V1-V14, prove the audit table is absent, apply V15, insert a binding and audit row, then verify JSON snapshots, indexes, and the action check constraint.

- [x] **Step 2: Run RED**

```bash
mvn -B -pl local-life-server -Dtest=CompensationCouponBindingMigrationIntegrationTest test
```

Expected: FAIL because V15/table does not exist.

- [x] **Step 3: Add the minimal migration, entity, and mapper**

Use one additive InnoDB table with `operator_user_id`, MDC `request_id`, nullable JSON snapshots, and `created_at`. Do not add audit update/delete APIs.

- [x] **Step 4: Run GREEN**

```bash
mvn -B -pl local-life-server -Dtest=CompensationCouponBindingMigrationIntegrationTest,CompensationCouponMigrationIntegrationTest test
```

Expected: all selected tests PASS.

- [x] **Step 5: Commit `feat(coupon): add durable binding audit schema`**

Record Goal, Changes, Verification, and Risk.

### Task 2: Authorized Binding Domain Service

**Interfaces**

- Produces `list(long shopId)`, `get(long shopId, int faceValueMinor)`, `upsert(long shopId, int faceValueMinor, long templateId)`, and `disable(long shopId, int faceValueMinor)`.
- Uses `MerchantService.requireApprovedMerchant()` and `ShopMapper.selectByIdForUpdate()`; no caller-provided merchant identity.
- Produces `READY`, `DISABLED`, `TEMPLATE_MISSING`, `TEMPLATE_INVALID`, or `MERCHANT_MISMATCH`.

- [x] **Step 1: Write failing service tests**

Cover: approved owner; non-merchant/unapproved; missing/foreign shop; non-positive face value; missing template; wrong shop/type/value/status; template conflict; CREATE/REPLACE/ENABLE/DISABLE audits; identical PUT/repeated disable no-op; missing binding; and server-derived merchant identity.

- [x] **Step 2: Run RED**

```bash
mvn -B -pl local-life-server -Dtest=CompensationCouponBindingServiceTest test
```

Expected: test compilation fails because the service/DTO contract does not exist.

- [x] **Step 3: Implement the smallest service and mapper surface**

Use one concrete service, MyBatis mappers, private validation/view helpers, and one private immutable audit-snapshot record. Do not introduce repository interfaces, factories, policy engines, or a generic audit framework. Write methods use `@Transactional(rollbackFor = Exception.class)`.

- [x] **Step 4: Run GREEN**

```bash
mvn -B -pl local-life-server -Dtest=CompensationCouponBindingServiceTest test
```

- [x] **Step 5: Commit `feat(coupon): manage owned-shop compensation bindings`**

### Task 3: Real MySQL Concurrency And Atomicity

**Interfaces**

- Consumes Tasks 1-2.
- Proves dual unique identities, per-shop serialization, no-op idempotency, and rollback with MySQL 8.4.

- [x] **Step 1: Write Testcontainers journeys**

Cover create, identical PUT, replace, disable twice, re-enable, two concurrent writes for one shop, independent shops, audit failure rollback, and visibility of disabled/invalid historical bindings.

- [x] **Step 2: Run the first persistence verification**

```bash
mvn -B -pl local-life-server -Dtest=CompensationCouponBindingPersistenceIntegrationTest test
```

Result: PASS (4/4). Task 2's implementation already satisfied the real MySQL
contract, so no production correction was justified.

- [x] **Step 3: Make only persistence corrections required by evidence**

Keep the shop row lock. Map duplicate-key races to `COUPON_COMPENSATION_BINDING_CONFLICT` without SQL details. Do not add distributed locks or retry libraries.

No correction was required: the shop row lock serialized both requests before
either unique key could race.

- [x] **Step 4: Run the real MySQL suite twice**

```bash
mvn -B -pl local-life-server -Dtest=CompensationCouponBindingPersistenceIntegrationTest test
mvn -B -pl local-life-server -Dtest=CompensationCouponBindingPersistenceIntegrationTest test
```

- [x] **Step 5: Commit `test(coupon): verify binding concurrency and audit atomicity`**

### Task 4: REST And Authentication Contract

**Interfaces**

- `GET /api/v1/shops/{shopId}/compensation-coupon-bindings`
- `GET /api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValueMinor}`
- `PUT /api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValueMinor}` with `{ "couponTemplateId": "4001" }`
- `PUT /api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValueMinor}/status/disabled`

- [x] **Step 1: Write failing MVC and auth-inventory tests**

Assert JSON ID strings, money fields, validation, error mapping, and service arguments. Add all four routes to `AuthInterceptorTest` as protected; nested GET must not match public `/api/v1/shops/*`.

- [x] **Step 2: Run RED**

```bash
mvn -B -pl local-life-server -Dtest=CompensationCouponBindingControllerTest,AuthInterceptorTest test
```

Expected: controller tests fail because the controller/DTO is absent; auth inventory additions pass independently.

- [x] **Step 3: Add minimal controller, request/response DTOs, and error codes**

The request contains only a positive `couponTemplateId`. Service validation remains fail-closed. Do not modify the public endpoint whitelist.

- [x] **Step 4: Run GREEN**

```bash
mvn -B -pl local-life-server -Dtest=CompensationCouponBindingControllerTest,AuthInterceptorTest test
```

- [x] **Step 5: Commit `feat(api): expose merchant compensation binding management`**

### Task 5: Cross-Service And Release Verification

- [ ] **Step 1: Update API reference, document index, design evidence, and completed plan checkboxes**

Document authorization, endpoint payloads, no-op semantics, errors, and audit. Do not add a large security document.

- [ ] **Step 2: Run full deterministic gates**

```bash
mvn -B -pl local-life-server clean verify
mvn -B -pl local-life-copilot test
python3 scripts/check_docs.py
git diff --check
```

Run the existing Server mutation command if `verify` does not execute it.

- [ ] **Step 3: Rebuild Docker Lite from current source**

```bash
docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app build locallife-server local-life-copilot
docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app up -d locallife-server local-life-copilot
docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app ps
```

- [ ] **Step 4: Run one isolated real journey**

Using a seeded APPROVED merchant token and owned shop: list, create/replace, identical replay, resolver READY, disable twice, resolver rejection, re-enable, exact DB binding/audit counts, and foreign-shop 403. Do not approve or execute compensation in this configuration smoke.

- [ ] **Step 5: Scope review and Draft PR**

Exclude secrets, logs, databases, artifacts, template CRUD, frontend, Agent/MCP mutation, and model eval changes. Commit docs with Goal/Changes/Verification/Risk, push `feat/compensation-binding-management-api`, create one Draft PR to `main`, and stop before Ready/merge.

---

## Self-Review

- Every approved authority, validation, concurrency, audit, error, rollback, and cross-service requirement maps to a task.
- Every production behavior begins with a named failing test and RED command.
- No generic repository, audit platform, distributed lock, pagination, frontend, bulk API, or dependency is added.
- IDs are `long` internally and strings in JSON; face values are positive `int` minor units; audit snapshots are JSON strings.
