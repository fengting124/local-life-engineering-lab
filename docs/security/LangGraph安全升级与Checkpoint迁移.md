# LangGraph 安全升级与 Checkpoint 迁移

- Status: Active
- Type: How-to
- Owners: Project maintainers
- Last verified: 2026-08-10
- Source of truth: `copilot-agent-service/session/checkpointer.py`, `copilot-agent-service/session/checkpoint_migration.py`, `local-life-copilot/src/main/resources/db/migration/V105__add_langgraph_typed_checkpoint_tables.sql`

本文说明 LangGraph 1.2.10 安全升级后的 typed Checkpoint 数据结构、历史数据迁移、发布和回滚流程。旧表必须保留；迁移期间不得放宽 serializer allowlist，也不得让新旧 Agent 同时写同一个 thread。

## 当前版本

```text
langgraph==1.2.10
langgraph-checkpoint==4.1.1
langchain-core==1.5.3
langchain-openai==1.4.1
langchain-anthropic==1.5.3
anthropic==0.120.2
```

部署必须设置：

```bash
LANGGRAPH_STRICT_MSGPACK=true
```

`AsyncMySQLCheckpointer` 使用 `JsonPlusSerializer`，关闭 `pickle_fallback`，不启用全局 JSON 或 msgpack module allowlist。完整 Checkpoint 和 pending writes 均通过 `dumps_typed/loads_typed` 读写。

## Typed Schema

V105 新增两张表，不修改 legacy 表：

| 表 | 主键 | typed 数据 |
| --- | --- | --- |
| `langgraph_checkpoint_v2` | `(thread_id, checkpoint_ns, checkpoint_id)` | `state_type` + `state_blob` |
| `langgraph_checkpoint_write_v2` | `(thread_id, checkpoint_ns, checkpoint_id, task_id, write_index)` | `value_type` + `value_blob` |

`checkpoint_ns` 缺省为 `""`，精确读取、最新读取、列表、pending writes 和父链都必须保留 namespace。`metadata` 只保存普通 JSON 字典；typed blob 不进行 UTF-8 decode、Base64 或 TEXT 转存。

旧 Saver 对 pending writes 一律使用顺序索引；Checkpoint 4.x 对 `__error__`、`__scheduled__`、`__interrupt__` 和 `__resume__` 使用官方 `WRITES_IDX_MAP` 固定负索引。迁移器会规范化这四类索引，普通通道继续保留旧索引；如果规范化后同一 task 出现主键冲突，整批迁移 fail closed，不能覆盖或猜测保留哪一条 write。

Legacy 表继续作为回滚源：

```text
langgraph_checkpoint
langgraph_checkpoint_write
```

## 迁移前提

1. 在 staging 或数据库副本先执行完整流程。
2. 备份 legacy 表、`hitl_approval` 和 Agent 会话表。
3. 暂停新 Agent 请求和 `/chat/resume`，确认没有正在执行的审批 lease。
4. 先应用 V105，确认两张 v2 表存在。
5. 使用迁移专用数据库账号；命令行不得打印 `DB_URL`。

Docker Lite 可用 db-init 幂等应用迁移：

```bash
docker compose \
  -f infra/docker-compose.dev.yml \
  -f infra/docker-compose.lite.yml \
  --profile app up -d --force-recreate db-init
docker wait local-life-db-init
```

## 迁移 Runbook

以下命令在 `copilot-agent-service` 目录执行，`DB_URL` 由受控环境注入：

```bash
python scripts/migrate-langgraph-checkpoints.py --dry-run
python scripts/migrate-langgraph-checkpoints.py --migrate
python scripts/migrate-langgraph-checkpoints.py --verify-only
```

小批量或单 thread 排查：

```bash
python scripts/migrate-langgraph-checkpoints.py --dry-run --limit 100
python scripts/migrate-langgraph-checkpoints.py --dry-run --thread-id THREAD_ID
```

三个模式的含义：

| 模式 | 读取 legacy | 写 v2 | 校验 v2 |
| --- | --- | --- | --- |
| `--dry-run` | 是 | 否 | 只验证可安全解码 |
| `--migrate` | 是 | 幂等 upsert | 写入前完成全部解码 |
| `--verify-only` | 是 | 否 | 比较 state、metadata、parent 和 writes |

任一 Checkpoint ID 不匹配、缺少必要字段、包含未解析 constructor、特殊 write 索引冲突或 typed roundtrip 不一致时，工具 fail closed、回滚当前事务并返回非零退出码。不能通过 `allowed_json_modules=True`、`allowed_msgpack_modules=True` 或 pickle fallback 绕过失败。

2026-08-10 Docker Lite 开发库实测：

```text
dry-run:    checkpoints=5844, writes=55743, failures=0
migrate:    checkpoints=5844, writes=55743, failures=0
verify-only checkpoints=5844, writes=55743, failures=0
```

这些数字只证明该次开发库迁移，不是生产数据量承诺。Testcontainers 还验证了重复迁移不增加行数，0.2.45 脱敏 fixture 验证了普通对话、工具调用、pending write、PENDING 审批、EXECUTED 重放、父链和精确恢复节点。

## Rollout

1. 先发布包含 V105 的 db-init，不切换 Agent。
2. 暂停 Agent 写入和审批恢复。
3. 依次执行 `dry-run`、`migrate`、`verify-only`，记录扫描、迁移、校验和失败数。
4. 校验 legacy/v2 行数、PENDING 审批绑定和抽样父链。
5. 发布新 Agent 镜像，确认容器内版本和 `LANGGRAPH_STRICT_MSGPACK=true`。
6. 执行健康检查、Checkpoint conformance、HITL 7 场景 smoke 和重启恢复。
7. 观察 Checkpoint 读写错误、serializer 拒绝、审批恢复 409 和数据库连接指标。
8. 稳定期结束前保留旧镜像、legacy 表和备份。

## Rollback

1. 立即停止新 Agent 流量和审批恢复，避免继续产生只存在于 v2 的会话。
2. 记录切换后创建的 session、thread 和 PENDING approval；这些记录不能静默降级给旧 Saver。
3. 恢复升级前 Agent 镜像。旧镜像继续读取未修改的 legacy 表。
4. 对切换后新建的审批执行人工撤销或在修复后重新发起，不复制未验证的 typed blob 回 legacy TEXT。
5. 保留 v2 表用于故障分析和重新迁移，不删除 V105 表或 legacy 数据。
6. 修复后重新执行完整 dry-run/migrate/verify，再恢复流量。

本方案不采用双写。双写会扩大序列化攻击面，并使两个版本对同一 thread 的 checkpoint 顺序产生歧义。

## 验证命令

```bash
pip check
DEBUG=false python -m pytest -q --cov --cov-fail-under=45
DEBUG=false mutmut run --max-children 4
python scripts/check_mutmut_score.py --min-kill-rate 50 --max-other 0
mvn -B -pl local-life-copilot clean verify
python3 scripts/hitl-security-smoke.py
python scripts/check_docs.py
git diff --check
```

## 已知限制

- 官方基础 conformance 已覆盖 `put`、`put_writes`、`get_tuple`、`list` 和 `delete_thread`；未实现当前业务不用的 `delete_for_runs`、`copy_thread` 和 `prune`。
- Compose 开发环境仍使用 MySQL root 账号；最小权限拆分不属于本 PR。
- 本次不迁移到 `interrupt()` / `Command(resume=...)`，HITL 仍使用现有 exact Checkpoint 恢复语义。

## 相关文档

- [安全升级设计](../superpowers/specs/2026-08-10-agent-langgraph-safe-upgrade-design.md)
- [RAG 依赖可达性审计](./RAG依赖可达性审计.md)
- [HITL 审批与安全恢复](./HITL审批与安全恢复.md)
- [历史安全公告评估](./LangGraph安全公告评估报告.md)
