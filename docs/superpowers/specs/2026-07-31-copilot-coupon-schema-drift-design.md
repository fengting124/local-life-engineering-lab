# Copilot Coupon Schema Drift Design

- Status: Approved
- Type: Bug fix design
- Owners: Project maintainers
- Last verified: 2026-07-31
- Source of truth: `coupon_template` migration, Server entity, Copilot mapper/DTO/tool, and the Case 32/37 baseline failures
- Target branch: `fix/copilot-coupon-schema-drift`
- Base: `main@3ef09afa043aa11cde965421b94cc9318826faaf`

## Problem

`coupon_template` stores remaining inventory in `remain_stock`, and the Server
entity maps it to `remainStock`. `CopilotCouponMapper` instead selects a
nonexistent physical column named `remaining_stock` in both coupon queries.
MySQL raises `Unknown column 'ct.remaining_stock'`, which propagates through
`coupon_policy_lookup` and deterministically causes the Case 32 and 37 tool
chains to be classified as `tool_execution_failure`.

The external contract is correct: the Copilot DTO uses `remainingStock` and the
MCP JSON response uses `remaining_stock`. Only the SQL-to-DTO boundary is wrong.

## Selected Approach

Use a real `mysql:8.4` Testcontainers database, apply the repository's existing
versioned Server and Copilot migrations with Flyway, seed a user, merchant,
shop, coupon template, seckill session, and issued coupon, then verify both the
real MyBatis mapper and the signed `POST /mcp` path with MockMvc.

The integration test keeps the real Spring application, `ToolRegistry`,
`CouponPolicyLookupTool`, MyBatis mapper, RBAC filter, JSON-RPC controller, and
MySQL. Redis-backed rate limiting and asynchronous audit persistence are test
replacements because neither participates in the field mapping contract and
both have dedicated tests.

Alternatives rejected:

1. Reuse the developer or CI MySQL service. This is not isolated and can pass
   because of local schema drift.
2. Test only the mapper with mocked rows. This cannot reproduce MySQL's unknown
   column behavior or prove the MCP JSON field.
3. Rename the database column. This expands the blast radius and breaks the
   already-correct Server contract.

## Data Contract

| Boundary | Required name |
| --- | --- |
| MySQL column | `remain_stock` |
| Server Java entity | `remainStock` |
| Copilot SQL alias | `remaining_stock` |
| Copilot DTO property | `remainingStock` |
| MCP JSON field | `remaining_stock` |

The corrected SQL expression is:

```sql
ct.remain_stock AS remaining_stock
```

It must be used by both `selectCouponTemplateById` and
`selectCouponTemplatesByMerchant`.

## Test Design

The integration test first lands while the mapper is still broken. Running the
direct mapper test must fail with a MySQL bad-SQL/unknown-column error. This is
the RED proof that the test observes the production defect.

After the two-line SQL repair, the same test must verify:

- all existing migrations execute against an empty MySQL 8.4 database;
- the actual schema contains `remain_stock` and not `remaining_stock`;
- the real mapper returns the seeded value through `remainingStock`;
- the merchant/status list query returns the same value;
- signed MCP calls for the two Case 32/37 coupon-policy shapes return
  `remaining_stock` in `result.content[0].text`;
- no Agent route, Prompt, Evidence Gate, scoring, HITL, schema, or production
  dependency is changed.

The Flyway libraries and Testcontainers modules are test-scoped and use Spring
Boot dependency-management versions. Test resources disable Flyway by default
so existing Spring tests that use the shared development database do not gain a
new migration lifecycle; this integration test explicitly enables it and
supplies the two repository migration locations.

## Verification

1. Targeted RED/GREEN integration test.
2. Complete `local-life-copilot` Maven test suite.
3. Relevant Python contract and evidence-normalization tests without a model
   call.
4. Docker Lite rebuild and health check for `locallife-copilot`.
5. Real signed MCP calls against the rebuilt container.
6. Documentation checks and Git diff checks.

No `24x2` DeepSeek baseline runs in this PR. The first post-fix model baseline
is deferred until this PR is merged into `main`.

## Scope Boundaries

Allowed production change:

- `local-life-copilot/.../CopilotCouponMapper.java`: two SQL expressions.

Allowed support changes:

- test-scoped Maven dependencies;
- one Testcontainers integration test and test-only configuration;
- this specification, implementation plan, and verification evidence.

Explicitly excluded:

- Agent routing, Prompt, Evidence Gate, EvalCase, fixtures, scoring, HITL;
- database migrations or physical column renaming;
- dependency version changes;
- unrelated refactoring or new Agent capability.
