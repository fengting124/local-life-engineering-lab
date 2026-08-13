# Agent 受控 RAG 派发报告

- Status: Active
- Type: Reference
- Owners: Agent maintainers
- Last verified: 2026-08-14
- Source of truth: `agent/nodes.py`, Docker Lite structured traces, and the ignored one-shot artifact
- Runtime source: `48433a9`
- Environment: Docker Lite, DeepSeek V4 Flash, concurrency 1

## 结论

当 Router 唯一选择 `knowledge_search` 时，Agent 现在直接从当前用户请求构造一个标准
`AIMessage(tool_calls=[...])`，再进入原有 `tool_node`。它没有直接调用 RAG，也没有绕过
ToolPolicy、RBAC、商家范围绑定、工具预算、审计、指标或 Evidence Gate。

PR #39 中的重复 `knowledge_search` 已消失。公开政策 3 次、无命中 2 次、商家私有
2 次和 CS 权限负例 2 次均满足执行安全合同；另加 1 次其他商家反向探针，未读到私有文档。

## Docker 证据

| 项目 | 值 |
| --- | --- |
| Public-path Agent image | `sha256:3215656930e62e48584865efecba1cd2fa4f4452532272f8d891b31e83ef46eb` |
| Clean final Agent image | `sha256:2cc7f1d34d8328dc033eb697295f56e81b2f27be74d7342ca4340bfa75227997` |
| Post-review Agent image | `sha256:cdf37b754fbe72873e8ce3548a95ac9ebdd299e4029a181b562b4046d6c6ecdc` |
| Runtime user | `appuser` |
| Services | MySQL、Redis、Server、Copilot、Agent、Embedding、Reranker healthy |
| Milvus Lite | `/app/data/local_life_kb.db`, volume `infra_agent_rag_data`, owner `appuser:appgroup` |
| Clean knowledge base | 2 public files, 7 chunks |
| Artifact | ignored `artifacts/performance/pr41-rag-public-20260814-030746` |

启动时 Agent 真实调用 Embedding 服务，并将公共文档写入 Milvus Lite。私有隔离验收使用
含一个唯一临时文档的短生命周期镜像；验收后停止 Agent 释放 Lite 文件锁，按 `doc_id` 删除 1 个 chunk，
删除临时 Markdown，再从干净源码重建。最终启动日志恢复为 `files=2, chunks=7`。

## 场景结果

| 场景 | 结果 | 工具 / LLM | 关键证据 |
| --- | --- | --- | --- |
| 公开政策 x3 | 3/3 `completed` | 每次 `knowledge_search` x1，LLM x1 | 每次都有 `tool.knowledge_search` 和真实 RAG span |
| 无命中 x2 | 2/2 `not_found` | 每次 `knowledge_search` x1，LLM x0 | Evidence Gate 直接终止，未生成无证据回答 |
| 目标商家私有 x2 | 2/2 `completed` | 每次 `knowledge_search` x1，LLM x1 | 私有文档绑定 `merchant_id=880000100001` 后命中 |
| 其他商家反向探针 x1 | 1/1 `not_found` | `knowledge_search` x1，LLM x0 | 相同查询未越权命中私有文档 |
| CS 权限负例 x2 | 2/2 SSE `permission_denied` | 工具 x0，LLM x0，RAG x0 | 在 native tool 构造和检索前终止 |

公开政策 3 次总延迟为 `12,109 / 12,862 / 13,510ms`，中位数 `12,862ms`；RAG
中位数 `2,395ms`，证据合成 LLM 中位数 `10,548ms`。每次只有 1 次模型调用，Token
分别为 `1,555 / 1,967 / 2,174`。本 PR 只修重复派发，不调整 RAG 或模型性能。

## 质量门禁

| 门禁 | 结果 |
| --- | --- |
| 路由/RAG/Evidence Gate 组合回归 | 379 passed |
| Post-review controlled knowledge regression | 11 passed（6 RED failures before fix） |
| Agent full suite | 817 passed |
| Coverage | 82.19%（门槛 45%） |
| Mutation | 860/1205 killed，71.4%，other=0（门槛 50%） |
| Docs / whitespace | passed |
| Duplicate controlled calls | 0 |
| Internal errors | 0 |
| Permission leakage | 0 |

独立审查发现并修复了三个合同边界：授权/路由工具超集、`None` 等畸形持久化状态、
以及查询首尾空白被改写。修复后的真实 Docker 回归中，授权路径为 `completed`，
`knowledge_search` x1、RAG x1、LLM x1；CS 负例为 `permission_denied`，工具、RAG、
LLM 均为 0。首次重建误注入了占位 API Key，DeepSeek 合成返回 401；恢复仓库外私密
配置后同一当前源码镜像通过，失败记录未被当作产品结果。

## 已知限制

- 这是定向功能验收，不是容量、P95/P99 或长稳测试。
- 有证据的知识回答仍保留一次 LLM 合成；延迟主要来自该调用和 Reranker。
- CS 权限负例的 `final_node` 与 SSE 均为 `permission_denied`，但 `/chat` 的
  `agent_run_measured.stop_reason` 被既有图结束汇总默认值记为 `completed`。执行安全没有
  放宽，结构化指标口径存在漂移；该逻辑已存在于 `origin/main`，不在本 RAG 派发 PR
  修改，后续应以独立可观测性修复处理。
- 原始 artifact 不提交，避免持久化请求、回答、业务 ID 和 trace ID。
