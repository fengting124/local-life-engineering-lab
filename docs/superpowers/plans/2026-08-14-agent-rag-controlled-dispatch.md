# Agent RAG Controlled Dispatch Implementation Plan

- Status: Active
- Type: Plan
- Owners: Agent maintainers
- Last verified: 2026-08-14
- Source of truth: `docs/superpowers/specs/2026-08-14-agent-rag-controlled-dispatch-design.md`
- Base: `main@e1f991437aa60cb55fc56373fbf2cf15d0710904`
- Branch: `fix/agent-rag-controlled-dispatch`

## Task 1: Reproduce and Freeze

- [x] Confirm PR #39 duplicate-call failure and exact existing execution path.
- [x] Freeze the single-tool knowledge allowlist and excluded modules.
- [ ] Add RED node and graph tests proving the current pre-search LLM call.

## Task 2: Minimal Dispatch

- [ ] Build one standard `knowledge_search` ToolCall from the current user query.
- [ ] Enter the existing `tool_node`; do not invoke RAG or MCP directly.
- [ ] Fail closed on invalid plan, permission, query or routed-tool state.
- [ ] Keep evidence-based synthesis and non-eligible ReAct behavior unchanged.

## Task 3: Deterministic Gates

- [ ] Cover normal, no-hit, merchant-private and permission-negative paths.
- [ ] Cover ToolPolicy denial, tool failure and two-tool/general fallback.
- [ ] Run focused tests, full Agent suite, coverage and mutation gate.
- [ ] Run docs check and `git diff --check`.

## Task 4: Docker Lite

- [ ] Rebuild Agent from current source and confirm Lite dependencies healthy.
- [ ] Run normal policy x3, no-hit x2, merchant-private x2 and permission-negative x2.
- [ ] Prove RAG execution, correct merchant scope, no duplicates and no leakage.

## Task 5: PR and Merge

- [ ] Record concise evidence and complete final read-only review.
- [ ] Create Draft PR, wait for CI, then merge only with `BLOCKING FINDINGS=0`.
- [ ] Wait for main CI and clean the branch/worktree.

