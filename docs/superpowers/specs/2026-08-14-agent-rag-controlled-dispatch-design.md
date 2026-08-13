# Agent RAG Controlled Dispatch Design

- Status: Approved
- Type: Design
- Owners: Agent maintainers
- Last verified: 2026-08-14
- Source of truth: `agent/nodes.py`, `agent/tool_router.py`, `rag/knowledge_tool.py`, and this contract
- Base: `main@e1f991437aa60cb55fc56373fbf2cf15d0710904`
- Branch: `fix/agent-rag-controlled-dispatch`

## Problem

PR #39 observed a controlled `knowledge` request where the model emitted two
identical `knowledge_search` calls. The existing `tool_node` correctly rejected
the batch as `controlled_tool_batch_rejected`, so RAG never executed. The Router
had already selected the only tool and the user request already contained its
only argument; the first LLM call had no remaining decision value.

## Frozen Boundary

Only the exact plan below is eligible:

```text
route_mode=controlled
route_task_type=knowledge
route_required_tools=[knowledge_search]
route_authorized_tools=[knowledge_search]
route_next_tool=knowledge_search
```

`policy_configuration` remains outside the path because its two-tool plan still
has product sequencing and synthesis behavior. General fallback, RAG retrieval,
embedding, Milvus, BM25, reranker, top-k, Prompt, model, RBAC, ToolPolicy,
Evidence Gate, Checkpointer, MCP/Java, HITL and Eval contracts are frozen.

## Dispatch Contract

The dispatch query is the exact non-empty content of the latest current
`HumanMessage`. It is not generated, rewritten or scope-expanded by the model.
The helper emits one standard `AIMessage(tool_calls=[...])` and enters the
existing `tool_node`. Native tool construction still binds the authenticated
state `merchant_id`; the LLM cannot supply or change it.

Missing current query, plan mismatch, unauthorized state or a routed-tool
mismatch fails closed as `internal_error`. CS remains unauthorized. A successful
search may retain one LLM call for evidence-based answer synthesis; no-hit ends
through the existing `not_found` path without fabrication.

## Verification

- RED proves the current route invokes the LLM and can emit duplicate calls.
- GREEN proves one standard call, exact query, zero pre-search LLM calls and
  unchanged native tool execution.
- Boundary tests cover no-hit, merchant isolation, CS denial, ToolPolicy,
  malformed state, complex/two-tool fallback and tool failure.
- Docker Lite runs normal policy x3, no-hit x2, merchant-private x2 and
  permission-negative x2. Duplicate calls, internal errors and permission leaks
  must remain zero, and RAG must actually execute when authorized.

