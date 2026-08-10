# LangGraph 安全升级设计

- Status: Approved
- Type: Explanation
- Owners: Project maintainers
- Last verified: 2026-08-10
- Source of truth: `copilot-agent-service/session/checkpointer.py`, Copilot Flyway migrations, `copilot-agent-service/tests/`

本文定义 PR #32 的边界：把 Agent 升级到 `langgraph==1.2.10` 和 `langgraph-checkpoint==4.1.1`，将自定义 MySQL Checkpointer 切换为严格 typed serializer，并迁移历史 Checkpoint。PR #30 已建立的 HITL 绑定、租约、幂等和结果重放语义保持不变。

## 依赖可达性审计

生产代码直接使用的包如下：

| 包 | 直接使用 | 证据 |
| --- | --- | --- |
| `langgraph` | 是 | `agent/graph.py`、`agent/nodes.py`、`session/checkpointer.py` |
| `langchain-core` | 是 | RunnableConfig、消息、工具协议 |
| `langchain-openai` | 是 | DeepSeek 的 OpenAI 兼容客户端 |
| `langchain-anthropic` | 是 | Anthropic provider |
| `langchain` | 否 | 全仓没有生产 import |
| `langchain-milvus` | 否 | 全仓没有生产 import |
| `pymilvus` | 是 | `rag/vector_store.py` 直接使用 `MilvusClient` |

因此本次删除两个无调用路径的直接依赖 `langchain` 和 `langchain-milvus`，保留 `pymilvus[milvus-lite]==2.4.9` 及现有 Lite 数据格式。PR #31 对 Milvus 3.x 的担忧来自解析器对未审计依赖的保守推断；本次用真实 import 图证明无需升级 Milvus，也不迁移 `local_life_kb.db`。

候选解析得到的直接版本为：

```text
langgraph==1.2.10
langgraph-checkpoint==4.1.1
langchain-core==1.5.3
langchain-openai==1.4.1
langchain-anthropic==1.5.3
anthropic==0.120.2
pymilvus[milvus-lite]==2.4.9
```

隔离环境 `pip check` 已通过。在未修改 Saver 时，现有 Checkpointer 的 4 个测试稳定因 `JsonPlusSerializer.dumps/loads` 已移除而失败；排除独立模型镜像和 Testcontainers 启动环境后，其他 Agent 测试无新增失败。

## 数据模型

旧表 `langgraph_checkpoint` 和 `langgraph_checkpoint_write` 只读保留，作为迁移源和回滚数据。V105 新建：

```text
langgraph_checkpoint_v2
  PK(thread_id, checkpoint_ns, checkpoint_id)
  parent_checkpoint_id
  state_type + state_blob
  metadata
  created_at

langgraph_checkpoint_write_v2
  PK(thread_id, checkpoint_ns, checkpoint_id, task_id, write_index)
  task_path + channel
  value_type + value_blob
  created_at
```

`checkpoint_ns` 缺省为空字符串，所有精确读取、最新读取、列表和 pending writes 查询都必须使用 namespace。新 blob 不进行 UTF-8 decode、Base64 或 TEXT 转存。

## 序列化边界

Saver 构造时显式调用 `BaseCheckpointSaver.__init__`，传入：

```python
JsonPlusSerializer(
    pickle_fallback=False,
    allowed_json_modules=None,
    allowed_msgpack_modules=None,
)
```

部署同时设置 `LANGGRAPH_STRICT_MSGPACK=true`。`None` 表示只接受依赖内建安全类型，并允许 LangGraph 在图编译时通过 `with_allowlist()` 生成最小派生 allowlist；禁止 `True` 和宽泛模块前缀。

完整 Checkpoint、metadata 和 pending writes 都使用 `dumps_typed/loads_typed`。Metadata 单独保持 JSON 列，仅存基础字典，不参与对象重建。`aput_writes` 对特殊 channel 使用 4.1.1 的 `WRITES_IDX_MAP` 固定负索引，普通 channel 才按输入位置编号。

## HITL 原子性

`aput` 仍在同一 SQLAlchemy 事务中完成：

```text
写入 typed checkpoint
  -> 从 checkpoint 提取 pending approval binding
  -> bind_checkpoint 校验 approval/thread/checkpoint/digest
  -> commit
```

绑定失败必须 rollback，不能留下未绑定的可恢复快照。恢复仍使用 PR #30 的 exact checkpoint、身份/商家/角色复验、CAS 执行租约、Server side-effect ledger 和结果重放；本 PR 不切换 `interrupt()` / `Command(resume=...)`。

## 历史迁移

独立 CLI 支持 `--dry-run`、`--migrate`、`--verify-only`、`--thread-id` 和 `--limit`。旧 TEXT 仅按 `loads_typed(("json", legacy_bytes))` 读取，再用严格 serializer 重新编码到 v2。

迁移原则：

- 源表不修改；目标 upsert 幂等。
- 每个 Checkpoint 校验 id、parent、namespace、pending writes 和恢复节点。
- 任一对象无法安全恢复时 fail closed，输出脱敏 id 与异常类型后终止正式迁移。
- `--verify-only` 比较结构和行数，不反序列化不受信任的任意模块。
- 回滚只需让旧版本继续读取旧表；v2 数据保留，不能删除旧表或旧行。

## 协议支持

本 PR 的官方基础门禁为 `put`、`put_writes`、`get_tuple`、`list` 和 `delete_thread`。`delete_for_runs`、`copy_thread`、`prune` 和 beta delta channel 不属于当前业务恢复路径，不为通过 conformance 盲目扩展；报告中必须明确为未实现能力。

## 验证边界

- Checkpointer：typed roundtrip、pending writes、namespace、精确读取、父链、特殊索引、重启恢复、HITL rollback、strict allowlist、官方基础 conformance。
- 迁移：脱敏 0.2.45 fixture 与 Testcontainers MySQL 的 dry-run/migrate/verify。
- HITL：现有定向测试和 Docker Lite 7/7。
- RAG：保持 PyMilvus 2.4.9，验证现有 `.db`、过滤、检索、删除和重启持久化。
- Provider：确定性 mock 验证创建、tool call、消息配对、streaming、usage 和错误分类。

## 非目标

不修改 Prompt、Router、Evidence Gate、ToolPolicy、RBAC、EvalCase、评分、模型参数、Java 服务或 RAG 算法；不运行 24x2 DeepSeek 基线；不迁移到官方 interrupt API；不删除 legacy 表；不转 Ready 或合并 PR。
