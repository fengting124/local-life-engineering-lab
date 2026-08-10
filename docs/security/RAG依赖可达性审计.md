# RAG 依赖可达性审计

- Status: Active
- Type: Reference
- Owners: Project maintainers
- Last verified: 2026-08-10
- Source of truth: `copilot-agent-service/rag/`, `copilot-agent-service/requirements-rag-local.txt`, Docker Lite persistence test

本文记录 LangGraph 安全升级对 RAG 依赖和 Milvus Lite 数据格式的实际影响。

## 审计结论

生产代码直接使用 `pymilvus.MilvusClient`，没有 import `langchain-milvus`；生产代码也没有 import 高层 `langchain` 包。因此本次删除两个无调用路径的直接依赖：

```text
langchain
langchain-milvus
```

继续保留：

```text
pymilvus==2.4.9
milvus-lite==2.4.12
```

PR #31 的 resolver 在保留 `langchain-milvus` 前提下推导出 PyMilvus/Milvus Lite 3.x。全仓 import 审计证明该前提不成立，因此无需升级到 3.x，也无需迁移现有 `local_life_kb.db`。这是新证据对旧假设的修正，不代表忽略了格式兼容风险。

## Setuptools 兼容约束

PyMilvus 2.4.9 在导入时仍使用 `pkg_resources`。Setuptools 81 及以上移除了该模块，因此本次固定：

```text
setuptools==80.9.0
```

这是一项兼容 pin，不是长期修复。升级 PyMilvus 前必须独立验证 Lite 3.x 文件格式，不得在 LangGraph PR 中顺手升级。

## 验证结果

| 项目 | 结果 |
| --- | --- |
| `pip check` | PASS |
| PyMilvus | 2.4.9 |
| Milvus Lite | 2.4.12 |
| 现有 `.db` 打开 | PASS |
| collection | `local_life_kb` |
| embedding dimension | 768 |
| upsert / delete | PASS |
| public filter | PASS |
| merchant filter | PASS |
| vector search | PASS |
| Docker 重启前后行数 | 7 / 7 |
| Docker 重启前后公共检索 | 命中相同脱敏文档 ID |
| 远端 HTTP URI 与 `no_proxy` | 现有配置测试 PASS |

Testcontainers/临时文件测试使用独立 collection 和临时 `.db`，不会修改开发 volume。Docker Lite 验证使用 `infra_agent_rag_data`，重启前后均保持 768 维、7 行并可检索。

## 运行边界

项目使用 `MILVUS_URI` 传递 Lite 文件路径。PyMilvus 2.4.x 自身也会在 import 阶段读取同名变量，但只接受 HTTP URI；`rag/vector_store.py` 在本地文件模式导入客户端时临时隐藏该环境变量，再将文件路径显式传给 `MilvusClient(uri=...)`。远端 HTTP 模式不修改环境变量。

直接在项目封装之外执行 `from pymilvus import MilvusClient` 且同时设置文件型 `MILVUS_URI` 不属于受支持路径，可能得到 `Illegal uri`。排障时应通过 `MilvusVectorStore` 或 Agent 的 RAG 链路验证。

## 后续升级门槛

若单独升级到 PyMilvus/Milvus Lite 3.x，必须先完成：

1. 复制现有 `.db`，禁止在原文件上试验。
2. 验证 2.4 文件能否读取；不能读取时设计导出/重建流程。
3. 比较 schema、索引、filter 语法和 delete 返回结构。
4. 验证 768 维 embedding、商家隔离和公共文档过滤。
5. 完成 Docker 重启持久化和回滚演练。

## 相关文档

- [LangGraph 安全升级与 Checkpoint 迁移](./LangGraph安全升级与Checkpoint迁移.md)
- [RAG 模型选型说明](../01-project/RAG模型选型说明.md)
- [历史 LangGraph 兼容矩阵](./LangGraph依赖兼容矩阵.md)
