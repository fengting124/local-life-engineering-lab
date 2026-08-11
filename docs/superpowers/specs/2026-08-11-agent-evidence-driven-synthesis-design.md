# Agent Evidence-Driven Synthesis Design

- Status: Approved
- Type: Design
- Owners: Project maintainers
- Last verified: 2026-08-11
- Baseline: `main@a939a7ac0db27527b88fa4898d936f28e216fc47`
- Source of truth: `copilot-agent-service/agent/evidence_gate.py`, `copilot-agent-service/agent/nodes.py`, latest sanitized 24x2 artifact
- Scope: PR #33 `fix/agent-evidence-driven-synthesis`

## Goal

Eliminate the six deterministic `synthesis_failure` results in Cases 16, 18, and 21 by making normalized tool evidence, rather than the LLM, decide which business facts appear in the final answer.

## Failure Evidence

The latest formal artifact is `artifacts/performance/agent-coupon-contract-20260731-211923/deepseek-flash-post-pr27.json`. It intentionally stores sanitized metrics only; raw answers and tool payloads are not retained, and the historical sessions have been cleaned. Therefore hallucination cannot be reconstructed after the fact and must not be claimed either way.

| Case | Request type | Actual trajectory | Required facts | Observed failure |
| ---: | --- | --- | --- | --- |
| 16 | paid order without coupon | `query_order -> query_coupon_issue_log`, both runs correct | `order_status=PAID` | final answer omitted a recognized paid-status fact |
| 18 | order/payment mismatch | `query_order -> query_payment`, both runs correct | `order_status=WAIT_PAY`, `payment_status=SUCCESS` | final answer omitted both required status facts |
| 21 | failed payment diagnosis | `query_order -> query_payment`, both runs correct | `payment_status=FAILED` | final answer omitted a recognized failed-status fact |

All six runs had first-tool, argument, and trajectory accuracy of 1.0 and no tool execution failure. The defect is after evidence collection.

## Boundary

This PR does not modify Router classification, Guardrails, ToolPolicy, RBAC, HITL, MCP behavior, Java services, EvalCase, scoring, RAG retrieval, Prompt routing strategy, or graph topology. It contains no case-ID branches and does not increase tool budgets.

## Design

Add one focused module, `agent/answer_facts.py`, that derives an immutable `EvidenceAnswer` from existing bounded `evidence_collected` state.

```text
evidence_collected
  -> task-specific fact selection
  -> canonical enum rendering
  -> deterministic final answer
  -> completeness/contradiction validation
  -> deterministic fallback
```

Supported diagnostic task types are:

| Task type | Required evidence rendered |
| --- | --- |
| `payment_diagnosis` | order status from `query_order`; payment status from `query_payment` |
| `coupon_issue` / `coupon_root_cause` | order status plus available coupon issue/usage facts |
| `mq_diagnosis` | order status plus dead-letter presence |

Only records with `status=success` are eligible. `UNKNOWN`, missing, malformed, or unsupported values are omitted and never guessed. Canonical status values map to fixed Chinese phrases while retaining the exact business meaning, for example `WAIT_PAY -> 待支付`, `SUCCESS -> 支付成功`, and `FAILED -> 支付失败`.

When `synthesis_only=true` and a supported diagnostic answer can be built, `llm_node` returns the deterministic answer directly. This removes one model call and prevents the model from choosing which facts to omit or invent. Unsupported task types retain the current path.

The validator accepts an answer only when every required fact is represented and no known contradictory status phrase is present. Otherwise it returns the deterministic rendering. Error, permission, not-found, timeout, business rejection, and HITL paths continue to be owned by existing nodes and are not overwritten.

## Testing

Unit tests cover required and optional facts, missing evidence, enum preservation, contradiction rejection, and fallback. Node and graph tests prove supported synthesis skips the LLM, performs no extra tool call, and leaves terminal error/policy behavior unchanged.

Real validation is limited to Cases 16, 18, and 21, at concurrency 1 and at most three repetitions each. A routing failure is recorded without Router changes. The full 24x2 baseline is explicitly deferred until PR #33 and PR #34 are both merged.

## Success Criteria

- Cases 16, 18, and 21 complete 3/3 each when routing reaches the expected tools.
- Required final facts are present and evidence-absent facts are not added.
- Tool sequences and permission/refusal behavior do not regress.
- Supported diagnostic synthesis uses zero additional LLM calls after evidence completion.
- Agent full tests, coverage, existing mutation gate, docs check, and `git diff --check` pass.
