# Engineering Hardening Execution Status

- Branch: `feature/engineering-hardening`
- Pull request: `#20`
- Updated: 2026-07-17

## Completed

| Item | Result |
|---|---|
| Plan | Full execution plan saved in `docs/01-project/13-面试导向工程加固执行计划.md` |
| A1 | Outbox leased claiming with `PROCESSING`, worker ownership, `FOR UPDATE SKIP LOCKED`, and expired lease recovery |
| A2 | Database-backed order idempotency ledger with request hash, lease, failure reclaim, and response replay |
| A3 | Multiple payment callback winner selection and `DUPLICATE_PAID` classification |
| B1 | VectorStore protocol and backend factory |
| B2 | Unified `MILVUS_URI`, Milvus Lite local default, and optional Standalone mode |
| Tests | Documentation, Python Agent, Java verify, database migrations, JaCoCo, and PIT gates passed for the completed code batches |

## Partial

| Item | Remaining work |
|---|---|
| B3 | Split base, local RAG, and local model dependencies into separate requirement sets |

## Pending

- A4 Redis Stream seckill delivery and reconciliation
- C1 Offline ingestion jobs and index versions
- C2 Persistent BM25 through Elasticsearch
- C3 Golden Set and calibrated RAG thresholds
- D1 Official LangGraph interrupt and resume
- D2 Token accounting and tool result compaction
- E1 Fault injection and concurrency tests
- E2 ADRs, benchmarks, and interview project cards
