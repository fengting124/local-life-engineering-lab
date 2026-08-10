# LangGraph 依赖兼容矩阵

- Status: Historical
- Type: Reference
- Owners: Project maintainers
- Last verified: 2026-08-05
- Source of truth: PyPI metadata, `pip --dry-run --report`, isolated virtual environments, Agent tests
- Superseded by: `docs/security/LangGraph安全升级与Checkpoint迁移.md`, `docs/security/RAG依赖可达性审计.md`

> 本文保留 PR #31 的升级候选研究。实施阶段证明 `langchain` 和 `langchain-milvus` 均无生产 import，因此没有采用本文在保留死依赖前提下推导出的 Milvus 3.x 组合。当前版本和运行证据见[安全升级文档](./LangGraph安全升级与Checkpoint迁移.md)与[RAG 依赖可达性审计](./RAG依赖可达性审计.md)。

本文记录 GHSA-g48c-2wqr-h844 和 GHSA-wwqv-p2pp-99h5 第一阶段使用的版本解析和兼容性证据。所有候选环境都位于系统临时目录，没有修改生产 `requirements*.txt`。

## 版本来源

执行时查询 [PyPI langgraph](https://pypi.org/project/langgraph/)、[PyPI langgraph-checkpoint](https://pypi.org/project/langgraph-checkpoint/)、[GHSA-g48c](https://github.com/advisories/GHSA-g48c-2wqr-h844) 和 [GHSA-wwqv](https://github.com/advisories/GHSA-wwqv-p2pp-99h5)：

- 当前版本：`langgraph==0.2.45`。
- GHSA-g48c 最低修复版本：`langgraph==1.0.10`。
- GHSA-wwqv 修复版本：`langgraph-checkpoint==3.0.0`。
- 2026-08-05 PyPI JSON 最新稳定版：`langgraph==1.2.10`，wheel 上传于 2026-07-28 且可下载。
- 两个候选都解析到 `langgraph-checkpoint==4.1.1`。
- 官方元数据的脱敏记录保存在 `docs/security/evidence/langgraph-official-release-advisory.txt`。

## 汇总

| 维度 | A：0.2.45 | B：1.0.10 | C：1.2.10 |
| --- | --- | --- | --- |
| GHSA-g48c | 受影响 | 已修复 | 已修复 |
| GHSA-wwqv | 受影响 | 已修复（Checkpoint 4.1.1） | 已修复（Checkpoint 4.1.1） |
| Python 要求 | 当前 3.10 可用 | 3.10 可用 | `>=3.10` |
| 原项目 pins 直接解析 | PASS | FAIL | FAIL |
| 协调升级后 `pip check` | 不适用 | PASS | PASS |
| Agent 模块导入 | PASS | PASS | PASS |
| 图编译 | PASS | PASS | PASS |
| Checkpointer 首次写入 | PASS | FAIL | FAIL |
| 0.2.45 fixture 读取与恢复节点 | PASS | 4/4 PASS | 4/4 PASS |
| 协调依赖现有测试 | 当前门禁见评估报告 | 654 PASS / 4 FAIL | 654 PASS / 4 FAIL |
| 可直接上线 | 否，存在安全风险 | 否，需要迁移 | 否，需要迁移 |

## 精确解析结果

### A：当前基线

```text
langgraph==0.2.45
langgraph-checkpoint==2.1.2
langchain-core==0.3.63
langchain==0.3.7
langchain-openai==0.2.6
langchain-anthropic==0.3.0
anthropic==0.40.0
openai==1.109.1
langchain-milvus==0.1.6
pymilvus==2.4.9
```

`pip check` 通过，导入、图编译和自定义 saver 协议均可运行。

### B：最低修复版本

只替换 `langgraph` 时解析失败。`langgraph-prebuilt>=1.0.8` 需要 `langchain-core>=1.0`，而当前 LangChain、OpenAI、Anthropic 和 Milvus 适配器共同要求 `langchain-core<0.4`。

协调解析结果：

```text
langgraph==1.0.10
langgraph-checkpoint==4.1.1
langgraph-prebuilt==1.0.13
langgraph-sdk==0.3.15
langchain-core==1.5.3
langchain==1.2.10
langchain-openai==1.4.1
langchain-anthropic==1.5.3
anthropic==0.120.2
openai==2.53.0
langchain-milvus==0.4.0
pymilvus==3.0.1
milvus-lite==3.1.1
```

### C：最新稳定版

只替换 `langgraph` 时同样解析失败。协调解析结果：

```text
langgraph==1.2.10
langgraph-checkpoint==4.1.1
langgraph-prebuilt==1.1.0
langgraph-sdk==0.4.2
langchain-core==1.5.3
langchain==1.3.14
langchain-openai==1.4.1
langchain-anthropic==1.5.3
anthropic==0.120.2
openai==2.53.0
langchain-milvus==0.4.0
pymilvus==3.0.1
milvus-lite==3.1.1
```

## 运行时不兼容

1.x 的 `JsonPlusSerializer` 只有 `dumps_typed/loads_typed`，已经移除 `dumps/loads`。当前 `AsyncMySQLCheckpointer` 在 `aput`、`aput_writes` 和 `_row_to_tuple` 中显式调用旧方法，因此图虽然能编译，第一次 Checkpoint 写入就会抛出：

```text
AttributeError: 'JsonPlusSerializer' object has no attribute 'dumps'
```

B、C 的现有测试结果相同：`654 passed, 4 failed`。四个失败全部来自 `tests/test_checkpointer.py` 对旧 serializer API 的真实使用，HITL 绑定、Router、Guardrail 和其余 Agent 测试没有额外失败。模型服务测试未纳入候选环境，因为临时环境没有安装独立模型进程使用的 `torch`；当前环境的正式门禁会单独运行。

2026-08-05 收口复核在全新隔离虚拟环境重跑候选 C：`pip --dry-run --report`、真实安装、`pip check`、wheel 下载、模块导入和图编译均通过；历史 fixture 为 `4 passed`，候选 Agent 测试仍为 `654 passed, 4 failed`。首次测试收集受宿主 `DEBUG=release` 干扰，明确使用测试布尔值 `DEBUG=false` 后得到上述有效结果；该前置配置错误未计入兼容失败。

## RAG 联动

当前 `langchain-milvus==0.1.6` 要求 `langchain-core<0.4`，与 LangGraph 1.x 的依赖树冲突。协调解析必须升级到 `langchain-milvus==0.4.0`，并连带 `pymilvus==3.0.1` 和 `milvus-lite==3.1.1`。

这意味着后续升级 PR 必须验证：

- Milvus Lite 2.4 数据文件能否原地读取；
- Collection schema、索引参数和查询返回结构是否变化；
- `langchain-milvus` 构造参数及 metadata filter 是否兼容；
- Docker Lite 重启持久化是否通过。

不能在只验证 Agent 图之后宣称升级完成。

## 序列化策略

`langgraph-checkpoint==4.1.1` 默认仍会警告后允许未注册 msgpack 类型；显式 `allowed_msgpack_modules=None` 或 `LANGGRAPH_STRICT_MSGPACK=true` 才会阻止测试 marker。JSON 反序列化默认只恢复安全类型，未注册 `Counter` 保持普通字典。

候选版本对 0.2.45 fixture 的 `loads_typed(("json", payload))` 均为 4/4 通过，包含 `HumanMessage`、`AIMessage`、`ToolMessage`、审批字段和 pending write。第四项把 0.2.45 真实最小图生成的四个 Checkpoint 交给候选图的 `get_state()`，恢复节点依次为 `__start__`、`first`、`second` 和结束状态。这证明样本数据可迁移，不证明当前旧表结构可直接继续运行。

## 选择

选择 `langgraph==1.2.10` 作为后续升级目标，并将 `langgraph-checkpoint>=3.0.0` 作为独立安全门槛；当前解析组合为 `langgraph-checkpoint==4.1.1`。原因是 B 和 C 的迁移范围相同，C 是执行时 PyPI 最新稳定版，并且没有理由在完成一次跨主版本迁移后停留在最低修复版本。

选择不等于本分支升级。后续实现必须单独设计 typed 存储格式、strict allowlist、历史数据迁移和 Milvus 联动验证。

## 复现命令口径

```bash
python -m pip install --dry-run --ignore-installed --report report.json ...
python -m pip check
python -m pytest -q --ignore=tests/test_embedding_service.py \
  --ignore=tests/security/test_langgraph_advisory.py
python -m pytest -q tests/security/test_langgraph_historical_fixture.py
```

`--ignore` 只用于候选临时环境的兼容性分解，不替代当前版本的正式全量测试门禁。
