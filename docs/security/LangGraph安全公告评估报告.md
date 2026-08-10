# LangGraph 安全公告评估报告

- Status: Active
- Type: Reference
- Owners: Project maintainers
- Last verified: 2026-08-05
- Source of truth: GHSA-g48c-2wqr-h844, GHSA-wwqv-p2pp-99h5, installed dependency source, Testcontainers MySQL tests, compatibility matrix

## 结论

```text
GHSA-g48c dependency affected: YES
GHSA-g48c msgpack production path reachable: NO
GHSA-wwqv dependency affected: YES
GHSA-wwqv legacy JSON production path reachable: YES, conditional on checkpoint-table write access
unauthenticated HTTP-to-checkpoint exploitability: UNPROVEN
selected upgrade target: langgraph==1.2.10
resolved checkpoint target: langgraph-checkpoint==4.1.1
historical checkpoint strategy: migrate
```

[GHSA-g48c-2wqr-h844](https://github.com/advisories/GHSA-g48c-2wqr-h844) 将 `langgraph<=1.0.9` 标记为受影响，首个修复版本为 `1.0.10`。项目当前使用 `0.2.45`，因此 msgpack 公告的依赖层结论为受影响。

生产自定义 MySQL saver 不使用 GHSA-g48c 描述的 msgpack typed 读取路径；向 `state` 写入 msgpack 后会在 JSON 解码阶段失败，`loads_typed` 调用数为零。

[GHSA-wwqv-p2pp-99h5](https://github.com/advisories/GHSA-wwqv-p2pp-99h5) 是独立的旧 JSON 模式公告，影响 `langgraph-checkpoint<3.0`，官方公告正文指定修复版本为 `3.0.0`。项目当前使用 `langgraph-checkpoint==2.1.2`，且普通 JSON `loads()` 会按持久化的模块名和类名动态导入并构造对象。隔离 MySQL 中篡改一条 Checkpoint 后，真实 `aget_tuple()` 已重建无害 `Counter` 标记。

因此最准确的风险描述是：

> GHSA-g48c 的 msgpack 生产路径不可达；GHSA-wwqv 的旧 JSON 生产路径在主体能控制持久化 Checkpoint 的前提下可达。两者不是同一公告的两种叫法。

PyPI JSON API 与可下载 wheel 确认 `langgraph==1.2.10` 于 2026-07-28 发布，为本次执行时最新稳定版。原始脱敏元数据见 `docs/security/evidence/langgraph-official-release-advisory.txt`。

## 环境

- 基线：`2ee8e88cb375ef82abb5f32db54bb6cf67872892`
- Python：3.10.12
- 当前版本：LangGraph 0.2.45、Checkpoint 2.1.2、Core 0.3.63
- `pip check`：通过
- 完整脱敏包清单：`docs/security/evidence/langgraph-current-environment.txt`

## 验证结果

| 门禁 | 命令口径 | 结果 |
| --- | --- | --- |
| 当前 Agent 全量测试与覆盖率 | `pytest -q --cov --cov-fail-under=45` | `665 passed, 1 skipped`，覆盖率 `76.42%` |
| Checkpointer/HITL/安全定向 | `pytest` 指定 Checkpointer、HITL 和 `tests/security` | 最终收口子集 `67 passed, 1 skipped` |
| Testcontainers MySQL | 全量和定向测试内执行 | JSON、msgpack、typed、pending writes 路径均按预期 |
| 候选 1.0.10 | 协调依赖、排除独立模型进程和当前漏洞复现文件 | `654 passed, 4 failed`，四项均为旧 saver API |
| 候选 1.2.10 | 同上 | `654 passed, 4 failed`，四项均为旧 saver API |
| 历史 fixture | 当前、1.0.10、1.2.10 | 三组均 `4/4 PASS`，含真实恢复节点计算 |
| Docker Lite HITL smoke | `python3 scripts/hitl-security-smoke.py` | `7/7 PASS`，隔离数据已清理 |
| Docker 健康 | Server、Copilot、Agent、MySQL、Redis | 全部 healthy，三个 HTTP health 均成功 |
| 文档和 whitespace | `python3 scripts/check_docs.py`、`git diff --check` | PASS |

当前版本 strict msgpack 测试为一项预期 skip，因为 2.1.2 不提供 allowlist 参数；两个修复候选均实际执行并通过该测试。

收口阶段对 1.2.10 又创建了全新隔离虚拟环境，实际下载 wheel 并重跑 resolver、安装、导入、图编译、首次 Checkpoint 写入、历史 fixture 和 Agent 候选测试。结果与原报告一致，因此 `654/4` 数字可归属于真实存在的 `langgraph==1.2.10`。

Docker smoke 首次调用在进入业务场景前失败，因为 worktree 不包含被 Git 忽略的环境文件，现有 `infra/.env` 也未声明 `HITL_PAYLOAD_SIGNING_SECRET`。第二次从正在运行的 Agent 容器直接传递既有值给测试进程后 `7/7 PASS`，过程没有输出或写入密钥。这个前置失败记录为本地配置漂移，不作为业务失败隐藏。

## 序列化事实

| 数据 | 写入 | 读取 | 存储 |
| --- | --- | --- | --- |
| 完整 Checkpoint | `serde.dumps` | `serde.loads` | `langgraph_checkpoint.state` LONGTEXT |
| Metadata | `json.dumps` | `json.loads` | `langgraph_checkpoint.metadata` JSON |
| Pending writes | `serde.dumps` | `serde.loads` | `langgraph_checkpoint_write.value` LONGTEXT |
| MemorySaver | `dumps_typed` | `loads_typed` | 进程内 bytes |

关键调用链：

```text
/chat/resume
  -> agent_graph.aget_state
  -> Pregel.aget_state
  -> checkpointer.aget_tuple
  -> AsyncMySQLCheckpointer._row_to_tuple
  -> JsonPlusSerializer.loads(state / pending write)
```

当前 `BaseCheckpointSaver.__init__` 对 serializer 做 typed 兼容包装，但项目的 saver 直接调用 `dumps/loads`。构造 saver 时不连接数据库，所以 `build_graph()` 不会因数据库不可用自动回退；实际读写错误发生在运行期。

## 无害复现结果

测试文件：`copilot-agent-service/tests/security/test_langgraph_advisory.py`。

| 场景 | 结果 | 解释 |
| --- | --- | --- |
| `CompiledGraph.aget_state` 委托 | PASS | 真实调用 `aget_tuple(config)`。 |
| GHSA-wwqv：普通 JSON Checkpoint 篡改 | REACHABLE | 生产 `_row_to_tuple` 重建无害 `Counter`。 |
| GHSA-g48c：msgpack 写入 `state` | NOT REACHABLE | `JSONDecodeError`，typed 调用为 0。 |
| GHSA-g48c：直接 `loads_typed(msgpack)` | AFFECTED | 当前依赖重建无害 `Counter`。 |
| GHSA-wwqv：Pending writes JSON 篡改 | REACHABLE | 生产 pending write 读取重建无害 `Counter`。 |
| GHSA-wwqv：Checkpoint 4.1.1 JSON 策略 | BLOCKED | 同一无害 constructor 保持为普通数据，不重建 `Counter`。 |
| GHSA-g48c：修复版 strict msgpack | BLOCKED | 未注册类型不再构造为 `Counter`。 |

测试不执行 shell、不读取环境变量、不访问网络。唯一外部资源是 Testcontainers 创建的临时 MySQL 8.4。Docker Desktop 当前无法暴露 Ryuk 的 8080 端口，因此本机命令显式设置 `TESTCONTAINERS_RYUK_DISABLED=true`；测试容器仍由上下文管理器退出清理，CI 保持 Testcontainers 默认行为。

## 可达条件

### 已证明

攻击或故障主体需要能修改以下任一列：

- `langgraph_checkpoint.state`；
- `langgraph_checkpoint_write.value`。

之后需要触发对应 thread 的状态读取，例如批准恢复调用 `agent_graph.aget_state`。对象重建发生在 HITL 载荷摘要和身份校验之前，因此后置绑定校验不能消除 serializer 风险。

### 未证明

没有发现 HTTP 接口接收原始 Checkpoint bytes 或 JSON 对象并直接写表。用户对话入口接收文本，MCP 结果也转换为文本内容。未建立从未认证外部请求到任意 constructor-shaped Checkpoint 数据的确定性链路，因此不能声明远程未认证可利用。

## 数据库信任边界

| 边界 | 当前事实 | 风险 |
| --- | --- | --- |
| Agent DB 账号 | Compose 使用 MySQL `root` | 应用被攻陷后拥有超出 Checkpoint 所需的权限。 |
| 宿主机端口 | `${MYSQL_PORT:-3306}:3306` | 默认绑定所有宿主机接口，取决于主机防火墙。 |
| 密码默认值 | 本地 Compose 和脚本存在开发默认值 | 不能用于共享或生产环境。 |
| 正常写入主体 | Agent 的 `aput/aput_writes` | 业务运行需要写 state 和 pending writes。 |
| 测试写入主体 | HITL smoke 可 `UPDATE/DELETE` Checkpoint | 有意用于篡改验证，但使用 root 且依赖环境指向。 |
| 外部 API | 不接受原始 Checkpoint | 未发现直接任意写入口。 |
| 管理/备份组件 | Compose 未发现 Adminer/phpMyAdmin/自动恢复写入 | 人工数据库工具仍属于运维信任边界。 |

测试和初始化脚本默认连接本地容器或 `localhost`，但多个脚本允许环境变量替换目标和密码。后续应增加测试环境 host/container allowlist 与显式确认，避免维护者把安全 smoke 指向非测试环境。

## 兼容性

详见 [LangGraph 依赖兼容矩阵](./LangGraph依赖兼容矩阵.md)。结论如下：

- 1.0.10 和 1.2.10 都不能直接替换当前 pin。
- 两者都需要 LangChain、provider SDK 和 Milvus 适配层联动升级。
- 两者都能导入并编译当前图。
- 两者都在首次自定义 Checkpoint 写入时失败，因为 `dumps/loads` 已移除。
- 两者都能通过 strict typed JSON 读取脱敏 0.2.45 fixture，未发现消息字段静默丢失或恢复节点漂移。

## 历史 Checkpoint 策略

选择 `migrate`，不选择原地兼容。

1. 暂停 Agent 写入和审批恢复，备份旧表。
2. 使用候选版本 strict JSON reader 读取旧 state 与 pending writes，只允许框架所需安全类型。
3. 遇到未注册类型时隔离记录并失败关闭，不用宽松 allowlist 批量放行。
4. 将数据写入带显式 type tag 的新表或官方 saver schema。
5. 校验 checkpoint ID、parent ID、channel values、pending writes 和预期恢复节点。
6. 在隔离副本完成 PENDING、EXECUTED 和父子链恢复测试。
7. 切换应用，再撤销旧 saver 数据库凭据和旧表写权限。

静态 fixture 证明“可迁移”，不证明所有线上历史对象都兼容。实际升级前必须对生产数据副本运行只读盘点。

## Rollout

1. 独立 PR 先实现新的 typed saver 和 strict allowlist，保持旧表只读。
2. CI 同时运行新旧 fixture 合同、HITL 并发恢复和 Docker Lite 重启测试。
3. 在 staging 复制脱敏历史数据，验证恢复节点与业务状态。
4. 停写、迁移、校验行数和父子链，再切换读写。
5. 观察反序列化阻断事件、Checkpoint 读写错误和审批恢复失败。
6. 稳定后撤销旧表写权限并清理过渡读取代码。

## Rollback

1. 切换前保留旧表不可变快照，不覆盖旧 state。
2. 新版本只写新 schema，避免旧二进制读取新格式。
3. 回滚时停止新写入，恢复旧版本和旧表只读快照。
4. 在回滚窗口内创建的新审批全部撤销并重新发起，不把新格式静默降级。
5. 轮换迁移账号和应用账号凭据。

## 建议优先级

### P0：后续升级 PR

- 目标 `langgraph==1.2.10`，同时确保 `langgraph-checkpoint>=3.0.0`；当前解析目标为 `4.1.1`。
- 迁移自定义 saver 到 typed API，并显式启用 strict msgpack。
- 对 JSON 类型使用最小安全 allowlist。
- 完成历史数据迁移和 Milvus 3.x 兼容验证。

### P1：数据库边界

- 为 Agent 创建最小权限账号，仅授予所需 Agent 表权限。
- 生产 MySQL 不映射公网宿主机端口。
- 安全 smoke 增加测试目标 allowlist 和生产环境拒绝开关。
- 将备份恢复账号与应用账号分离并审计。

## 本阶段未修改

生产依赖、Agent 图、Prompt、Router、RAG、工具权限、EvalCase、评分规则、HITL API 和数据库权限均未改变。本阶段没有运行真实模型基线。
