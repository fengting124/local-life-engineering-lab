# HITL 审批与安全恢复

- Status: Active
- Type: How-to
- Owners: Agent and MCP maintainers
- Last verified: 2026-08-13
- Source of truth: `copilot-agent-service/session/hitl.py`, `copilot-agent-service/session/checkpointer.py`, `copilot-agent-service/api/chat.py`, `local-life-copilot/.../hitl/`, `local-life-server/.../InternalService.java`

本文说明退款和补偿券审批的安全边界、恢复流程、排障查询和事故处理。它描述的是当前已实现行为，不代替 IAM、数据库备份或密钥管理制度。

## 1. 安全目标

一个人工审批只能授权一次不可变的高风险动作。执行前必须同时满足：

```text
审批表规范化载荷
= 审批绑定的精确 Checkpoint 中的 pending_action
= Agent 恢复时构造的执行参数
= Java MCP 收到的参数
= Server 副作用账本中的请求
```

任意环节不一致都要在业务副作用发生前失败。当前保护的工具是：

- `execute_refund`
- `issue_compensation_coupon`

退款继续使用原有 payload v1。补偿券使用 payload v2，在共同字段之外还签名绑定
`shop_id`、`coupon_template_id`、`coupon_discount_type`、
`coupon_min_order_amount`、`coupon_valid_days` 和 `coupon_terms_digest`。
审批卡片直接显示这些已签名稳定条款；执行时 Server 重读模板并重算摘要。

## 2. 组件职责

| 边界 | 职责 | 代码入口 |
| --- | --- | --- |
| Agent | 规范化业务目标，生成 HMAC-SHA256 摘要，创建审批 | `session/hitl_binding.py`、`session/hitl.py` |
| Checkpointer | 在同一数据库事务中保存真实 LangGraph 快照并绑定精确 `checkpoint_id` | `session/checkpointer.py` |
| Resume API | 从审批记录读取 `thread_id + checkpoint_id`，验证载荷、身份、权限和摘要 | `api/chat.py`、`HitlService.validate_resume()` |
| Copilot MCP | 在调用主服务前再次验证并以 CAS 获取执行租约 | `ApprovalExecutionGuard.java` |
| LocalLife Server | 用 `(operation_type, approval_id)` 唯一账本去重；补偿券在同一事务内原子扣库存、写 `user_coupon` 并完成账本 | `InternalService.java`、`side_effect_ledger` |

Agent 不信任恢复请求中的业务参数；Copilot 不只信任 `approval_id`；Server 不把网络重试当作新业务请求。

## 3. 完整流程

```text
用户明确提出订单和金额
  -> Agent 查询订单并用 (shop_id, amount_minor) 调 admin-only resolver
  -> resolver 唯一确定模板、可读条款和条款摘要
  -> Agent 生成 ApprovalPayload 和 HMAC 摘要
  -> hitl_approval=PENDING，checkpoint_id=NULL
  -> hitl_node 输出 pending_hitl=True
  -> Checkpointer 保存完整快照并原子绑定 checkpoint_id
  -> 人工审批 CAS：PENDING -> APPROVED
  -> /chat/resume 按审批记录加载精确快照
  -> 校验 action/payload/identity/merchant/role/digest/permission
  -> Agent 只用已验证的服务端载荷恢复
  -> Copilot 再校验并 CAS：APPROVED -> EXECUTING
  -> 调用 Server，Server 以 approval_id 写副作用账本
  -> Copilot 保存脱敏结果：EXECUTING -> EXECUTED
  -> 网络重试读取 EXECUTED 结果或 Server 账本结果，不重复生效
```

审批记录先以 `checkpoint_id=NULL` 创建，是为了避免把尚未持久化的快照宣称为恢复点。只有包含 `pending_hitl=True`、审批 ID 和摘要的完整快照写入成功，Checkpointer 才在同一事务里完成绑定。未绑定审批不能通过。

## 4. 状态机和 CAS

| 当前状态 | 允许迁移 | 条件 | 失败结果 |
| --- | --- | --- | --- |
| `PENDING` | `APPROVED` | 未过期、已绑定 Checkpoint、已有版本和摘要 | 保持 `PENDING` 或标记 `EXPIRED` |
| `PENDING` | `REJECTED` | 审批者首次成功更新 | 保持原终态 |
| `APPROVED` | `EXECUTING` | 摘要相等、未过期，CAS `rowcount=1` | 重读并返回进行中或重放 |
| `EXECUTING` | `EXECUTING` | 两分钟租约已过期且摘要相等 | 活跃租约返回进行中 |
| `EXECUTING` | `EXECUTED` | `execution_id` 与当前租约所有者相等 | 完成被拒绝并告警 |
| `EXECUTED` | 不再执行 | 读取已存脱敏结果 | 返回重放结果 |

核心 CAS 不是先查后改，而是把状态条件放在 SQL `WHERE` 中：

```sql
UPDATE hitl_approval
SET status = 'EXECUTING', execution_id = ?, execution_lease_until = ?
WHERE id = ?
  AND status = 'APPROVED'
  AND expire_at >= ?
  AND payload_digest = ?;
```

应用以 `rowcount` 判定唯一获胜者。数据库唯一账本则处理“Server 已提交，但上游没有收到响应”的模糊结果。

## 5. 失败与恢复矩阵

| 场景 | 对外结果 | 审批/业务保证 |
| --- | --- | --- |
| 缺少签名密钥 | 非开发环境启动失败；Compose 配置失败 | 不生成可执行审批 |
| 创建审批失败 | `internal_error` | 不发出可操作的 HITL 事件 |
| Checkpoint 写入或绑定失败 | 安全失败 | 审批不可通过或不可恢复 |
| 快照不存在或不可解码 | HTTP 409 `checkpoint_missing` | 审批不消费，副作用为 0 |
| Checkpoint 载荷被改 | HTTP 409 `payload_mismatch`/`digest_mismatch` | 审批保持未消费，副作用为 0 |
| 用户、角色或商家变化 | HTTP 409 `identity_mismatch` 或权限拒绝 | 必须重新授权 |
| 审批过期或被拒绝 | 恢复拒绝 | 高风险工具执行为 0 |
| 两个恢复请求并发 | 一个获得租约，其余进行中或重放 | 账本最多一条正常副作用 |
| Server 已提交、Copilot 超时 | 租约到期后重试 | Server 账本返回首次结果 |
| Copilot 已完成、Agent 超时 | 再次恢复 | 返回 `EXECUTED` 的脱敏结果 |
| Agent 在审批后重启 | 精确 Checkpoint 恢复 | 不取 thread 的任意最新快照 |

这里的“恰好一次”只表示同一审批的 Server 业务调用由唯一账本和
`user_coupon.issuance_key=COMPENSATION:{approval_id}` 去重，不代表跨数据库、日志、
SSE 和所有外部系统具有全局分布式 exactly-once。补偿券的库存、用户券和账本在
同一 Server 事务中提交；用户通知尚未接入，不能宣称已完成消息触达。

## 6. 日志、审计与排障

用 `trace_id` 关联 Agent run、MCP 工具审计和容器日志。审批摘要、签名、token、secret、password、cookie、authorization、API key 和 internal key 会在 `tool_audit_log` 中递归替换为 `[REDACTED]`。

```sql
-- 审批当前状态、绑定和执行租约
SELECT id, thread_id, checkpoint_id, action_type, status,
       requested_user_id, requested_role, merchant_id,
       execution_id, execution_lease_until, executing_at, executed_at,
       expire_at, updated_at
FROM hitl_approval
WHERE id = ?;

-- 精确恢复点是否存在
SELECT thread_id, checkpoint_id, parent_checkpoint_id, created_at
FROM langgraph_checkpoint
WHERE thread_id = ? AND checkpoint_id = ?;

-- 工具审计；不要直接导出完整 tool_input/tool_output
SELECT trace_id, session_id, thread_id, tool_name, status, duration_ms, created_at
FROM tool_audit_log
WHERE session_id = ?
ORDER BY created_at;

-- Server 副作用是否只有一条
SELECT operation_type, approval_id, status, resource_id, created_at, updated_at
FROM side_effect_ledger
WHERE approval_id = ?;

-- 补偿券业务效果与库存
SELECT id, coupon_template_id, source_type, source_approval_id, issuance_key
FROM user_coupon
WHERE source_approval_id = ?;

SELECT remain_stock FROM coupon_template WHERE id = ?;
```

容器日志入口：

```bash
docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml \
  --profile app logs --no-color --since=15m copilot-agent locallife-copilot locallife-server
```

建议告警：

- `payload_mismatch`、`digest_mismatch`、`identity_mismatch` 在短时间内集中出现；
- `EXECUTING` 且 `execution_lease_until < NOW()` 长时间未恢复；
- `EXECUTED` 审批没有对应 `side_effect_ledger`，或同一审批出现多个 operation；
- 高风险工具审计缺少 `trace_id`；
- 审计写入失败日志；
- Checkpoint 加载/反序列化失败。

## 7. Docker Lite 验证

先通过被 Git 忽略的 `infra/.env` 或当前 shell 注入同一个随机密钥，不能提交真实值：

```bash
read -rsp 'HITL signing secret: ' HITL_PAYLOAD_SIGNING_SECRET
export HITL_PAYLOAD_SIGNING_SECRET
python3 scripts/hitl-security-smoke.py
python3 scripts/compensation-coupon-smoke.py
```

脚本覆盖拒绝、退款、补券、Agent 重启、并发恢复、真实 Checkpoint 单字段篡改和 Server 已提交后重试。原始报告写入被忽略的 `artifacts/security/hitl-<timestamp>/`，不得提交数据库文件、密钥或完整业务载荷。

2026-08-13 的真实 Lite 补偿券验证覆盖 7 个业务场景。成功场景库存
`20 -> 19`、`user_coupon 0 -> 1`、账本 `0 -> 1`；同审批并发和重复调用均只保留
一个业务效果并重放同一 coupon ID；库存不足为 `EXECUTION_FAILED` 且零副作用；
门店/条款篡改在执行前拒绝；Server 已提交但上层结果未知时从账本恢复；7 条高风险
工具审计未泄漏审批摘要。脚本仅删除自己的唯一前缀数据。

V14 不是双版本滚动迁移。部署时必须暂停全部 `user_coupon` 写入，包括普通秒杀券：

```text
先构建新 Server/Copilot/Agent
-> 停止三个应用服务，暂停全部券写入
-> 执行 V14，并重复执行一次确认 schema_migrations 幂等跳过
-> 启动新镜像
-> 验证 SECKILL issuance_key 和真实 COMPENSATION 旅程
-> 恢复券写入
```

日常恢复只重启常驻服务，不要对带 `--profile app` 的全部服务无差别执行
`restart`，因为 `db-init` 是绑定迁移文件的一次性容器：

```bash
docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml \
  --profile app restart mysql redis locallife-server locallife-copilot copilot-agent
```

`hitl_approval` 的 `DATETIME` 字段统一写入无时区的 UTC 值，Python 和 Java 的过期判断也都以 UTC 为基准。将过期审批从 `PENDING` 改为 `EXPIRED` 时使用第二次 CAS；若并发的批准或拒绝已先成功，过期路径必须回滚并重读，不能覆盖最新状态。

## 8. 密钥管理与轮换

`HITL_PAYLOAD_SIGNING_SECRET` 是 Agent 和 Copilot 共享的审批 HMAC 密钥。它应由部署平台的 Secret Manager 注入，只授予两个服务读取权限，禁止写入 Git、镜像层、日志、PR 或测试报告。

当前实现只有单个活动密钥，没有 `key_id` 和双密钥验证窗口。直接轮换会使旧 `PENDING/APPROVED` 审批无法通过摘要验证。生产轮换必须选择一种显式策略：

1. 先停止创建新审批，处理或作废旧审批，再同时更新两个服务并重启。
2. 另开实现任务增加 `key_id` 和限时双密钥验证，再做无停机轮换。

密钥疑似泄露时，应立即阻止新审批、撤销未执行审批、轮换密钥、核对审批/工具审计/账本，并按 `trace_id` 保存调查证据。

## 9. 事故处理清单

1. 记录 UTC 时间、`approval_id`、`thread_id`、`run_id` 和 `trace_id`，不要粘贴完整载荷或密钥。
2. 查询审批状态、租约、精确 Checkpoint 和 Server 账本。
3. 在确认 Server 是否已提交前，不手工重置 `EXECUTING`，也不创建第二个审批补做。
4. 若账本为 `SUCCESS`，走结果重放；若无账本且租约仍有效，等待当前执行者完成。
5. 若租约过期且无业务结果，使用原审批和原摘要恢复，不修改载荷。
6. 发现 digest/identity/checkpoint mismatch 时保持 fail-closed，撤销审批并重新发起。
7. 检查工具审计的 trace 关联和敏感字段脱敏；保存脱敏后的日志证据。
8. 修复后运行定向测试和 `scripts/hitl-security-smoke.py`，不要用删除健康检查或清空业务卷掩盖问题。

## 10. 已知边界与安全跟踪

- 入口身份仍使用当前 `X-User-*` 信任链；生产还需要网关鉴权、短时服务身份和 mTLS 等入口治理。
- 运行时尚未迁移到 LangGraph 官方 `interrupt()/Command(resume=...)`；当前安全性依赖自定义精确 Checkpoint 契约及测试。
- 工具审计是异步 fail-open：审计数据库写失败会告警，但不会回滚已经成功的业务调用。
- 当前单密钥设计不支持无停机轮换。
- 补偿券已真实落库，但用户通知尚未接入。
- 补偿模板映射没有管理 API；当前由迁移/运维 SQL 预配置 `(shop_id, face_value_minor)`。

## 11. 面试常问

**为什么不用普通 SHA-256？**

普通哈希只能发现偶然变化，能写数据库的人可以改载荷后重新计算。HMAC 还需要服务端密钥，用于证明摘要由受信服务生成。摘要比较使用常量时间 API，减少时序侧信道。

**为什么用数据库 CAS，不在 Java 里加锁？**

应用锁只保护单进程。多副本和重启场景必须由共享数据库在一条带状态条件的更新中选出唯一执行者，并检查 `rowcount`。

**CAS 已经保证 exactly-once 了吗？**

CAS 保证同一时刻最多一个有效执行租约，属于执行权控制。网络可能在 Server 提交后丢失响应，因此还要用 Server 唯一账本去重并重放结果。补偿券另外使用唯一 issuance key，库存、用户券和账本在一个事务中提交。

**为什么恢复不能只用 thread 的最新 Checkpoint？**

同一 thread 后续可能产生其他快照。审批授权的是当时的具体载荷，必须读取审批记录绑定的精确 `checkpoint_id`，否则会把旧审批套到新状态上。

**网络超时后为什么不能直接再退款一次？**

超时代表结果未知，不代表失败。应先查审批租约和 Server 账本；若首次调用已提交，就重放原结果，而不是生成第二个副作用。
