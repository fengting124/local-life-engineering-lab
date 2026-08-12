# DeepSeek Agent Performance Baseline: deepseek-flash-post-product

- Generated: `2026-08-12T09:38:00`
- Provider: `deepseek`
- Model: `deepseek-v4-flash`
- Cases: `24`
- Total runs: `48`
- Invalid eval contracts: `0`
- Fixture resolution: `1.000`
- Stored data: sanitized metrics only; prompts, answers, tool payloads, and keys are not persisted.

| Concurrency | Runs | Success | Task Done | First Tool | Args | Trajectory | Facts | Permission | HITL | Latency P95 | First SSE P95 | Failures |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 48 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 21508 ms | 69 ms | {} |

## Per-case result matrix

| Case | Iteration | Outcome | Stop | Tools | Failure |
| ---: | ---: | --- | --- | --- | --- |
| 1 | 1 | success | fast_path | shop_metrics_query | PASS |
| 2 | 1 | success | completed | shop_metrics_query | PASS |
| 3 | 1 | success | fast_path | shop_metrics_query | PASS |
| 4 | 1 | success | completed | query_order | PASS |
| 5 | 1 | success | completed | query_order -> query_payment | PASS |
| 6 | 1 | success | completed | shop_metrics_query | PASS |
| 16 | 1 | success | completed | query_order -> query_coupon_issue_log | PASS |
| 17 | 1 | permission_denied | permission_denied | query_order | PASS |
| 18 | 1 | success | completed | query_order -> query_payment | PASS |
| 19 | 1 | clarification | clarification | - | PASS |
| 20 | 1 | success | completed | query_order -> query_mq_dead_letter | PASS |
| 21 | 1 | success | completed | query_order -> query_payment | PASS |
| 31 | 1 | success | completed | knowledge_search | PASS |
| 32 | 1 | success | completed | knowledge_search -> coupon_policy_lookup | PASS |
| 33 | 1 | success | completed | knowledge_search | PASS |
| 34 | 1 | success | completed | knowledge_search | PASS |
| 35 | 1 | success | completed | knowledge_search | PASS |
| 36 | 1 | success | completed | knowledge_search | PASS |
| 37 | 1 | success | completed | knowledge_search -> coupon_policy_lookup | PASS |
| 46 | 1 | refusal | guardrails_blocked | - | PASS |
| 47 | 1 | refusal | guardrails_blocked | - | PASS |
| 48 | 1 | success | completed | - | PASS |
| 49 | 1 | not_found | not_found | query_order | PASS |
| 50 | 1 | refusal | guardrails_blocked | - | PASS |
| 1 | 2 | success | fast_path | shop_metrics_query | PASS |
| 2 | 2 | success | completed | shop_metrics_query | PASS |
| 3 | 2 | success | fast_path | shop_metrics_query | PASS |
| 4 | 2 | success | completed | query_order | PASS |
| 5 | 2 | success | completed | query_order -> query_payment | PASS |
| 6 | 2 | success | completed | shop_metrics_query | PASS |
| 16 | 2 | success | completed | query_order -> query_coupon_issue_log | PASS |
| 17 | 2 | permission_denied | permission_denied | query_order | PASS |
| 18 | 2 | success | completed | query_order -> query_payment | PASS |
| 19 | 2 | clarification | clarification | - | PASS |
| 20 | 2 | success | completed | query_order -> query_mq_dead_letter | PASS |
| 21 | 2 | success | completed | query_order -> query_payment | PASS |
| 31 | 2 | success | completed | knowledge_search | PASS |
| 32 | 2 | success | completed | knowledge_search -> coupon_policy_lookup | PASS |
| 33 | 2 | success | completed | knowledge_search | PASS |
| 34 | 2 | success | completed | knowledge_search | PASS |
| 35 | 2 | success | completed | knowledge_search | PASS |
| 36 | 2 | success | completed | knowledge_search | PASS |
| 37 | 2 | success | completed | knowledge_search -> coupon_policy_lookup | PASS |
| 46 | 2 | refusal | guardrails_blocked | - | PASS |
| 47 | 2 | refusal | guardrails_blocked | - | PASS |
| 48 | 2 | success | completed | - | PASS |
| 49 | 2 | not_found | not_found | query_order | PASS |
| 50 | 2 | refusal | guardrails_blocked | - | PASS |
