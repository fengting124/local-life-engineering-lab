# Copilot Coupon Schema Drift Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- Status: Complete; Draft PR #27 open
- Type: Plan
- Owners: Project maintainers
- Last verified: 2026-07-31
- Source of truth: `docs/superpowers/specs/2026-07-31-copilot-coupon-schema-drift-design.md`

**Goal:** Repair the Copilot coupon inventory field mapping and prove it through a real MySQL mapper and signed MCP request path.

**Architecture:** A test-scoped Testcontainers MySQL 8.4 instance is migrated from the existing Server and Copilot migration directories. One Spring Boot integration test exercises the real MyBatis mapper and MCP controller while replacing only Redis rate limiting and asynchronous audit persistence. Production behavior changes only in the broken coupon mapper projections.

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis, MySQL 8.4, Testcontainers, Flyway, MockMvc, JUnit 5, AssertJ.

## Global Constraints

- Work only on `fix/copilot-coupon-schema-drift`; never push directly to `main`.
- Do not modify Agent routing, Prompt, Evidence Gate, Eval scoring, HITL, database schema, or dependency versions.
- Do not run the `24x2` DeepSeek baseline in this PR.
- Keep all new dependencies test-scoped and versionless under the existing Spring Boot BOM.
- Production code changes only in `CopilotCouponMapper`: map inventory through
  `ct.remain_stock AS remaining_stock` and remove stale template
  `start_time`/`end_time` projections proven absent by the same migrations.
- Preserve `remaining_stock` as the MCP JSON contract.

---

### Task 1: Add Isolated MySQL Contract Test Infrastructure

**Files:**
- Modify: `local-life-copilot/pom.xml`
- Modify: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/audit/ToolAuditServiceIntegrationTest.java`
- Create: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/domain/mapper/CopilotCouponSchemaContractIntegrationTest.java`

**Interfaces:**
- Consumes: Server migrations `V1` through `V13` and Copilot migrations `V101` through `V103`.
- Produces: a MySQL-backed Spring test context with real `CopilotCouponMapper`, `ToolRegistry`, and `/mcp` endpoint.

- [x] **Step 1: Add test-only dependencies**

Add `org.testcontainers:junit-jupiter`, `org.testcontainers:mysql`,
`org.flywaydb:flyway-core`, and `org.flywaydb:flyway-mysql` with `test` scope and
no explicit versions.

- [x] **Step 2: Isolate Flyway activation**

Disable Flyway locally on the existing audit integration test:

```java
@SpringBootTest(properties = "spring.flyway.enabled=false")
```

The new integration test explicitly enables Flyway and supplies absolute
filesystem locations for both migration directories. A test-side
`application.yml` was rejected after verification showed that it shadows the
main datasource configuration.

- [x] **Step 3: Write the failing real-MySQL mapper test**

Create a `@SpringBootTest`, `@AutoConfigureMockMvc`, and `@Testcontainers` test
with a static `MySQLContainer<>("mysql:8.4")`. Use `@DynamicPropertySource` for
the datasource and Flyway locations. Seed IDs unique to this test:

```text
user=910000000001
merchant=920000000001
shop=930000000001
coupon_template=940000000001
seckill_session=950000000001
user_coupon=960000000001
remain_stock=37
```

Call `selectCouponTemplateById(940000000001L)` and assert
`getRemainingStock() == 37`.

- [x] **Step 4: Run RED verification**

Run:

```bash
mvn -B -pl local-life-copilot -Dtest=CopilotCouponSchemaContractIntegrationTest#mapperReadsPhysicalRemainStock test
```

Expected: FAIL from MySQL/MyBatis because `ct.remaining_stock` does not exist.
Record the first relevant `Caused by` line; do not change the assertion to fit
the broken query.

### Task 2: Apply the Minimal Mapper Repair

**Files:**
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/domain/mapper/CopilotCouponMapper.java`
- Test: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/domain/mapper/CopilotCouponSchemaContractIntegrationTest.java`

**Interfaces:**
- Consumes: MySQL `coupon_template.remain_stock`.
- Produces: `CouponTemplateSnapshot.remainingStock` via SQL alias `remaining_stock`.

- [x] **Step 1: Repair both projections**

Replace each broken projection with:

```sql
ct.remain_stock AS remaining_stock,
```

The next GREEN attempt exposed `Unknown column 'ct.start_time'`. Migration V4
shows that campaign time windows are stored in the one-to-many
`seckill_session` table, so both stale template time projections were removed
instead of fabricating dates or selecting an arbitrary session.

- [x] **Step 2: Run GREEN mapper verification**

Run the same targeted Maven command. Expected: the mapper test passes and
returns `37`.

- [x] **Step 3: Add list-query coverage**

Call `selectCouponTemplatesByMerchant(920000000001L, "ACTIVE")` and verify one
row with coupon ID `940000000001` and remaining stock `37`.

- [x] **Step 4: Verify the physical schema contract**

Use `JdbcTemplate` against `information_schema.columns` and assert
`remain_stock` exists while `remaining_stock` does not.

### Task 3: Prove the Signed MCP Case 32/37 Path

**Files:**
- Modify: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/domain/mapper/CopilotCouponSchemaContractIntegrationTest.java`

**Interfaces:**
- Consumes: signed JSON-RPC `tools/call` requests for `coupon_policy_lookup`.
- Produces: MCP `result.content[0].text` JSON containing `remaining_stock=37`.

- [x] **Step 1: Add a signed MCP request helper**

Generate the existing HMAC-SHA256 identity signature over:

```text
user_id + "\n" + role + "\n" + merchant_id + "\n" + timestamp
```

Use admin for the single-coupon shape and merchant `920000000001` for the
merchant-list shape.

- [x] **Step 2: Add Case 32-shaped tool execution**

POST `/mcp` with `coupon_policy_lookup` and
`coupon_template_id=940000000001`. Parse `result.content[0].text` and assert
`coupon_template_id` and `remaining_stock=37`.

- [x] **Step 3: Add Case 37-shaped tool execution**

POST `/mcp` with `coupon_policy_lookup` and `status=ACTIVE` as the seeded
merchant. Parse the nested coupon list and assert count `1`, merchant ownership,
and `remaining_stock=37`.

- [x] **Step 4: Run the complete integration class**

Run:

```bash
mvn -B -pl local-life-copilot -Dtest=CopilotCouponSchemaContractIntegrationTest test
```

Expected: mapper, schema, and both MCP shapes pass without `Unknown column` or
`tool_execution_failure`.

### Task 4: Run Full Regression and Docker Lite Smoke

**Files:**
- Update: this plan with exact verification evidence.

**Interfaces:**
- Consumes: completed mapper fix and tests.
- Produces: local and container verification evidence for the Draft PR.

- [x] **Step 1: Run complete Java tests**

```bash
mvn -B -pl local-life-copilot clean test
```

- [x] **Step 2: Run relevant Python deterministic tests**

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest -q \
  tests/test_evidence_gate.py tests/test_eval_contract.py tests/test_eval_scoring.py
```

No model call is allowed.

- [x] **Step 3: Rebuild and restart Copilot in Docker Lite**

```bash
docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml \
  --profile app build locallife-copilot
docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml \
  --profile app up -d locallife-copilot
```

Wait for `local-life-copilot` to report healthy. Do not restart unrelated
services unless Compose dependency health requires it.

- [x] **Step 4: Execute signed real-container MCP smoke**

Use the repository HMAC helper and an existing development coupon fixture to
call `coupon_policy_lookup` through `http://localhost:8081/mcp`. Confirm the
response contains `remaining_stock` and logs contain no unknown-column error.

- [x] **Step 5: Run repository checks**

```bash
python3 scripts/check_docs.py
git diff --check
```

### Verification Evidence

- RED: MySQL 8.4 failed first on `Unknown column 'ct.remaining_stock'` and then
  exposed the pre-existing stale `ct.start_time` projection.
- GREEN: the complete mapper/schema/signed-MCP integration class passed 5/5
  after all 16 migrations (Server V1-V13 and Copilot V101-V103) ran from an
  empty schema.
- Java: `mvn -B -pl local-life-copilot clean test` passed 88/88.
- Python: evidence gate, Eval contract, and scoring tests passed 98/98 without
  a model call.
- Docker: standard Dockerfile built image
  `sha256:68c6cbcf0b7100034eaa25b139a86ed79184d7722bd0e14ac93a2246f4aeb8f4`;
  MySQL, Redis, Server, Copilot, and Agent were healthy.
- Real MCP: Case 32-shaped lookup returned coupon `881300000000` with
  `remaining_stock=100000`; Case 37-shaped merchant lookup returned two owned
  coupons and the same first-row inventory. No unknown-column error appeared.

Known out-of-scope observations retained for follow-up: the shell HMAC helper
does not emit its optional `X-Merchant-Id` header, so the Docker smoke supplied
that signed header explicitly; hand-written MCP requests without a session ID
also expose the existing non-null `tool_audit_log.session_id` contract warning.
Neither issue changes coupon query success and neither is repaired in this PR.

### Task 5: Publish a Draft PR

**Files:**
- Update: this plan's checkboxes and evidence notes only.

**Interfaces:**
- Consumes: verified branch commits.
- Produces: Draft PR to `main`; no merge and no model baseline.

- [x] **Step 1: Review scope and secrets**

Confirm the production diff contains only the scoped Mapper projection repair
and no API key, `.env`, database file, log, or generated report is tracked.

- [x] **Step 2: Commit with traceable messages**

Use Goal, Changes, Verification, and Risk sections in every commit. Keep test
infrastructure, mapper repair, MCP proof, and final evidence logically reviewable.

- [x] **Step 3: Push the feature branch**

```bash
git push -u origin fix/copilot-coupon-schema-drift
```

- [x] **Step 4: Create Draft PR #27**

Target `main`, include RED unknown-column evidence, GREEN mapper/MCP proof, full
test and Docker results, and state that the `24x2` DeepSeek baseline was not run.

- [x] **Step 5: Wait for PR checks**

Keep the PR Draft. Report CI results and residual boundaries; do not merge
without explicit approval.

Draft PR #27 is open at
`https://github.com/fengting124/local-life-engineering-lab/pull/27`.
At verification head `780f1778ef7f2898f1997791e3231da0fb019fd6`, Docs CI and the
Copilot test job both completed successfully; the latter initialized service
containers, applied database migrations, and ran the test suite. GitHub emitted
only its repository-level Node.js 20 action deprecation annotation. The PR
remains Draft and unmerged.
