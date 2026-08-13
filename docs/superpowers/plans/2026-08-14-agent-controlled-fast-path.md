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
- [x] Add RED node tests proving order, payment and coupon controlled routes
  currently invoke the LLM to emit predetermined ToolCalls.
- [x] Add RED graph tests for zero LLM, exact ordered tools and complete facts.

## Task 2: Minimal Dispatch

- [x] Add one private deterministic dispatch helper in `agent/nodes.py`.
- [x] Call it only after existing tool discovery and ToolRouter filtering.
- [x] Emit standard `AIMessage(tool_calls=[...])`; never call MCP directly.
- [x] Fail closed on eligible binding mismatch; preserve ReAct fallback for
  non-eligible routes.

## Task 3: Safety and Product Regressions

- [x] malformed ID clarification and valid not-found terminal.
- [x] CS/admin permissions and ToolPolicy denial remain unchanged.
- [x] binding mismatch, missing tool and tool error fail closed.
- [x] complex/ambiguous, refund, compensation and RAG remain outside the path.

## Task 4: Deterministic Gates

- [x] Run focused node/graph/router/policy/evidence/answer tests.
- [x] Run Agent full suite and coverage: `802 passed`, `81.15%`.
- [x] Run existing mutation gate without lowering its threshold: `71.3%`.
- [x] Run docs check and `git diff --check`.

## Task 5: Docker Lite Measurement

- [x] Rebuild Agent image from current branch and confirm required services
  healthy.
- [x] Run only `order_lookup`, `payment_diagnosis`, `coupon_diagnosis`, three
  times each, concurrency 1.
- [x] Record total latency, LLM calls, tokens, ordered tools and final facts.
- [x] Compare with PR #39 fixed observations and require at least 80% latency
  reduction without quality or safety regression.

## Task 6: Report and Draft PR

- [x] Add a concise performance comparison report and update this plan.
- [x] Perform final read-only production diff review: `BLOCKING FINDINGS=0`.
- [ ] Push branch and create a Draft PR to `main`.
- [ ] Stop without Ready or merge.
