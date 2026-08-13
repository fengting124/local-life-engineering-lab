# Agent Controlled Fast Path Implementation Plan

- Status: Active
- Type: Plan
- Owners: Agent maintainers
- Last verified: 2026-08-14
- Base: `main@566c7fe886ef552582827ecc82443e7c0cdc68b9`
- Branch: `perf/agent-controlled-fast-path`
- Source of truth: `docs/superpowers/specs/2026-08-14-agent-controlled-fast-path-design.md`

## Task 1: Freeze Scope and RED Contract

- [x] Record allowlist, bindings, failure matrix and frozen modules.
- [ ] Add RED node tests proving order, payment and coupon controlled routes
  currently invoke the LLM to emit predetermined ToolCalls.
- [ ] Add RED graph tests for zero LLM, exact ordered tools and complete facts.

## Task 2: Minimal Dispatch

- [ ] Add one private deterministic dispatch helper in `agent/nodes.py`.
- [ ] Call it only after existing tool discovery and ToolRouter filtering.
- [ ] Emit standard `AIMessage(tool_calls=[...])`; never call MCP directly.
- [ ] Fail closed on eligible binding mismatch; preserve ReAct fallback for
  non-eligible routes.

## Task 3: Safety and Product Regressions

- [ ] malformed ID clarification and valid not-found terminal.
- [ ] CS/admin permissions and ToolPolicy denial remain unchanged.
- [ ] binding mismatch, missing tool and tool error fail closed.
- [ ] complex/ambiguous, refund, compensation and RAG remain outside the path.

## Task 4: Deterministic Gates

- [ ] Run focused node/graph/router/policy/evidence/answer tests.
- [ ] Run Agent full suite and coverage.
- [ ] Run existing mutation gate without lowering its threshold.
- [ ] Run docs check and `git diff --check`.

## Task 5: Docker Lite Measurement

- [ ] Rebuild Agent image from current branch and confirm required services
  healthy.
- [ ] Run only `order_lookup`, `payment_diagnosis`, `coupon_diagnosis`, three
  times each, concurrency 1.
- [ ] Record total latency, LLM calls, tokens, ordered tools and final facts.
- [ ] Compare with PR #39 fixed observations and require at least 80% latency
  reduction without quality or safety regression.

## Task 6: Report and Draft PR

- [ ] Add a concise performance comparison report and update this plan.
- [ ] Perform independent read-only review; require `BLOCKING FINDINGS=0`.
- [ ] Push branch and create a Draft PR to `main`.
- [ ] Stop without Ready or merge.
