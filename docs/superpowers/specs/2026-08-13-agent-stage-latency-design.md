# Agent Stage Latency Design

- Status: Approved
- Type: Design
- Owners: Agent maintainers
- Last verified: 2026-08-13
- Source of truth: `copilot-agent-service/agent/trace.py`, `agent/metrics.py`, runtime logs, and the PR #39 measured artifact

## Goal

Measure where representative Docker Lite Agent requests spend time and tokens
without changing Agent behavior or adding an observability service. The result
must distinguish LLM, tools, RAG, routing, HITL preparation, and unclassified
graph/runtime overhead well enough to select the next latency optimization.

## Existing Signals

The implementation must extend, not replace, the current signals:

- `genai_span` already emits structured start/end events for `llm.invoke`,
  `tool.<tool_name>`, `mcp.rpc`, and all RAG stages.
- `record_tool_call` updates low-cardinality Prometheus counters/histograms.
- `record_llm_call` and the LLM token/latency metrics exist but have no
  production caller, so those counters currently do not increase.
- `llm_node` reads provider `usage_metadata`; `token_count` is a context-budget
  proxy and is not a reliable billing total.
- `agent_run`, runtime events, SSE `session_started`, and structlog context
  already expose run/session/thread/trace correlation.
- Fast Path metrics execute before LangGraph and currently have no stage-level
  timing. RAG already has detailed spans; no parallel RAG timer is needed.

## Event Contract

All new measurements use the existing `genai_span_start` / `genai_span_end`
format. The minimum added spans are:

| Span | Boundary |
| --- | --- |
| `request.total` | accepted `/chat` request through terminal SSE/HITL/error |
| `session.prepare` | session message persistence and runtime-run creation |
| `router.classify` | deterministic Fast Path attempt plus normal classification |
| `graph.total` | `agent_graph.astream_events` lifetime |
| `mcp.list_tools` | the full cache-hit or cache-miss `list_tools` call |
| `hitl.prepare` | approval creation, signing, persistence, and response preparation |

Span attributes may contain `session_id`, `thread_id`, `trace_id`, low-cardinality
route/stage status, step, provider, and model. They must not contain Prompt,
answer, tool argument values, tool result payloads, API keys, order numbers, or
user identifiers. High-cardinality correlation fields remain structured-log
attributes and never become Prometheus labels.

Each real LLM call emits a sanitized `llm_call_measured` event with step,
provider, model, duration, and nullable input/output/total tokens. It also calls
the existing `record_llm_call` only when both input and output token counts are
valid non-negative integers. Missing or malformed usage is logged as
`usage_status=missing`; it is never converted to zero. The existing
`llm_response` log no longer emits the raw provider metadata object.

## Failure Semantics

Instrumentation is best-effort. Logging or Prometheus failures are caught and
reported through a bounded warning that contains no request content; they must
not alter an LLM response, tool result, HITL state, or SSE terminal event.
Application exceptions still propagate through the existing business path, and
the enclosing span records `status=error`.

## Lightweight Collector

`scripts/profile-agent-latency.py` sends one request at a time to the real
Docker Lite Agent with a unique test-only `X-Trace-Id`. It consumes SSE only to
obtain run/thread identifiers, terminal reason, and safe tool names. It then
reads only matching JSON span/measurement log lines from the Agent container.
No database schema or performance endpoint is added.

The committed artifact contains only:

- scenario name, role, route type/mode, stop reason, and success/failure;
- total and stage durations, LLM/tool names and counts;
- nullable input/output/total token counts;
- runtime/image/model identifiers and timestamp.

It excludes the original request, response text, tool inputs/results, API keys,
and business identifiers. The collector validates this schema before writing
JSON and Markdown.

## Measurement Set

Run concurrency one and one observation each for approximately ten fixture-backed
scenarios: merchant today/month metrics Fast Path, order lookup, valid missing
order, payment diagnosis, coupon diagnosis, coupon root cause, RAG knowledge,
campaign draft, explicit refund to PENDING, and compensation to PENDING. The
last two are never approved. A scenario may be repeated once only to diagnose a
measurement failure, and that repeat must be disclosed.

For each run, compute total, router, list-tools, LLM, tool, RAG, HITL, and
`other = max(0, total - non-overlapping top-level stages)`. Nested `mcp.rpc` and
RAG subspans support diagnosis but are not double-counted in total shares.

## Scope Boundaries

This PR does not modify Prompt, Router decisions, Evidence Gate, RBAC, HITL
semantics, MCP business logic, RAG ranking, Checkpoint protocol, Eval contracts,
or tool budgets. It does not add OpenTelemetry Collector, Jaeger, LangSmith, a
new service, a database migration, or a deterministic Fast Path. It runs no
24x2 baseline and makes no quality-improvement claim.

## Acceptance

The final report must answer whether LLM dominates the observed tail, which
tasks use the most calls and tokens, Fast Path versus ReAct difference,
`list_tools` fixed cost, RAG share, whether graph overhead exceeds the 15-20%
follow-up threshold, and the top three next Fast Path candidates. Full Agent,
coverage, mutation, docs, and diff gates must pass with zero secret or payload
leakage and `BLOCKING FINDINGS=0` before a Draft PR is created.
