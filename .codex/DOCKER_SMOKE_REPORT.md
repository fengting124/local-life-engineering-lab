# Docker Lite Smoke Test Report

## 基本信息
- 分支：fix/docker-lite-runtime
- 基线：main@46281c1322c3164125f3e99c04584e3beec1f6b7
- 验证工作区：56843ac4373bedc638f916485b0d183bbb2d4c20 之后的 Docker Lite runtime 修复；最终提交以 Git 历史为准
- 操作系统：WSL Ubuntu 22.04 on Docker Desktop
- Docker 版本：28.1.1
- Docker Compose 版本：v2.35.1
- 执行时间：2026-07-18 02:00-03:23 Asia/Shanghai

## 实际启动命令
- Compose 文件组合：`-f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app`
- pull 命令：`docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app pull`
- server build 命令：`DOCKER_BUILDKIT=1 docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app build --no-cache locallife-server`
- copilot/agent build 命令：`docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app build locallife-copilot copilot-agent`
- up 命令：`docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app up -d ...`
- restart 命令：`docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app restart`

## 镜像结果
| 镜像/服务 | 拉取 | 构建 | 说明 |
| --- | --- | --- | --- |
| mysql | PASS | N/A | `mysql:8.4` healthy。 |
| redis | PASS | N/A | `redis:7.4-alpine` healthy。 |
| locallife-server | N/A | PASS | 标准 Dockerfile no-cache 从当前源码构建成功；最终一次 Maven `BUILD SUCCESS`，server 镜像导出为 `locallife-server:latest`。 |
| locallife-copilot | N/A | PASS | 当前源码重建成功，容器 healthy。 |
| copilot-agent | N/A | PASS | 当前源码重建成功，容器 healthy；Milvus Lite 依赖可加载。 |

## 容器结果
| 服务 | 状态 | Health | 端口 | 说明 |
| --- | --- | --- | --- | --- |
| mysql | Up | healthy | 3306 | 完整重启后仍 healthy。 |
| redis | Up | healthy | 6379 | 完整重启后仍 healthy。 |
| local-life-server | Up | healthy | 8080 | 完整重启后 healthy。 |
| local-life-copilot | Up | healthy | 8081 | 完整重启后 healthy。 |
| copilot-agent-service | Up | healthy | 8000 | 完整重启后 healthy，Milvus Lite connected。 |

## 烟雾测试
| 测试项 | 结果 | 证据 |
| --- | --- | --- |
| Java health | PASS | `/actuator/health` 返回 UP，db/redis UP。 |
| Copilot health | PASS | `local-life-copilot` healthy。 |
| Agent health/OpenAPI | PASS | `/health` 返回 OK，`/openapi.json` 返回 200。 |
| 空库迁移 | PASS | 临时 project `local-life-migration-smoke` 全新 MySQL 执行 V1-V13、Copilot V101-V103 成功。 |
| 迁移重复执行 | PASS | 第二次执行全部显示已执行并跳过。 |
| V11/V12/V13 | PASS | `order_idempotency`、`outbox_message` lease 字段和 `idx_outbox_claim`、`seckill_reservation` 均查到。 |
| 下单幂等 | PASS | 同一 `X-Idempotency-Key` 两次返回订单 `2078198526896709633`；分表仅 1 条业务订单；账本 `SUCCESS` 且 `response_json` 存在。 |
| 幂等冲突 | PASS | 同 key 不同 body 返回 `400 SYS_PARAM_INVALID`。 |
| 重复支付 | PASS | 两笔支付单：一笔 `SUCCESS`，另一笔 `DUPLICATE_PAID`；订单状态 `PAID`。 |
| Payment Outbox | PASS/LIMITED | 正常支付成功只产生一条 `payment-success-topic` Outbox，状态 `PENDING`。Lite 不启动 RocketMQ、不运行发送验证，不虚构 `SENT`。 |
| 秒杀 Redis Stream | PASS | `/api/v1/seckill` 返回 success，`XLEN seckill:stream = 1`，结果 key 为 `PENDING`。 |
| 秒杀 Outbox | PASS/LIMITED | 秒杀 event Outbox 写入 `PENDING`；Lite profile 关闭调度，`seckill_reservation` 未由 recovery worker 写入。 |
| 完整重启恢复 | PASS | `docker compose ... restart` 后 mysql/redis/server/copilot/agent 均 healthy，业务数据和 Milvus 文件保留。 |

## Milvus Lite 持久化
- URI：`/app/data/local_life_kb.db`
- volume 名：`infra_agent_rag_data`
- 宿主机路径：`/var/lib/docker/volumes/infra_agent_rag_data/_data`
- 容器路径：`/app/data`
- 重启前检索：写入 collection `docker_lite_smoke_20260717`，命中 `docker-lite-smoke-doc-20260717-1806`。
- 重启后检索：Agent restart 后再次命中同一文档。
- 清理：验证后 drop 测试 collection；未提交 `.db` 文件。
- 额外证据：完整重启后日志包含 `milvus_connected` 和 `knowledge_base_ingested`，`/app/data/local_life_kb.db` 归属 `appuser:appgroup`。

## 数据库迁移
- 已执行版本：server V1-V13；copilot V101-V103。
- V11 结果：`order_idempotency` 存在，包含 `response_json`、`lease_until`、`expires_at` 和相关索引。
- V12 结果：`outbox_message` 包含 `worker_id`、`lease_until`、`claimed_at` 和 `idx_outbox_claim`。
- V13 结果：`seckill_reservation` 存在。
- 是否可重复执行：PASS，第二次迁移全部跳过。

## 发现的问题
| 严重级别 | 问题 | 根因 | 修复状态 |
| --- | --- | --- | --- |
| High | Agent 健康但 Milvus Lite 未实际可用 | `pymilvus 2.4.x` import 阶段读取 `MILVUS_URI` 环境变量，只接受 HTTP URI；本项目传本地 DB 路径。 | 已修复：本地文件 URI 创建客户端时临时隐藏 `MILVUS_URI`。 |
| High | Agent 无法写 `/app/data` | named volume 目录为 root 所有。 | 已修复：Dockerfile 预创建并 chown `/app/data`；现有 volume 做非破坏性 chown。 |
| Medium | Milvus Lite 多 worker 文件锁冲突 | Agent 默认 2 workers，同一 Lite `.db` 只能由一个进程打开。 | 已修复：Lite Compose 将 Agent 限为 1 worker。 |
| High | 当前开发库缺 V11/V12/V13 | 业务 volume 未执行最新迁移。 | 已修复：对当前开发库执行幂等 `init-db.sh`，随后重启 server 让 ShardingSphere 重新加载元数据。 |
| High | 下单失败：`order_no` 无默认值 | 代码先 insert 再 update 回填 orderNo，MySQL strict mode 拒绝。 | 已修复：插入前显式生成 orderId/orderNo。 |
| High | 发起支付失败：`payment_no` 无默认值 | 支付单同样先 insert 再 update 回填 paymentNo。 | 已修复：插入前显式生成 paymentId/paymentNo。 |
| High | Mock 支付回调写 Outbox 失败 | `triggerMockPay()` 自调用 `handleCallback()`，事务 AOP 未生效，Outbox `MANDATORY` 找不到事务。 | 已修复：`triggerMockPay()` 自身加事务。 |
| Medium | Outbox 无法验证 SENT | Lite 不启动 RocketMQ；调度关闭。 | 已标记受限：只验证 PENDING 写入，不虚构发送成功。 |

## 修改文件
- `copilot-agent-service/Dockerfile`
- `copilot-agent-service/rag/vector_store.py`
- `copilot-agent-service/tests/test_vector_store_config.py`
- `infra/docker-compose.lite.yml`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/order/service/OrderService.java`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/order/service/PaymentService.java`
- `.codex/DOCKER_SMOKE_REPORT.md`

## 最终结论
PARTIAL：Lite 镜像构建、启动、健康检查、空库迁移、Milvus Lite 持久化、下单幂等、重复支付、Payment Outbox PENDING、秒杀 Redis Stream 和完整重启恢复均通过；但 Lite 环境不启动 RocketMQ/调度 worker，因此 Outbox `PROCESSING/SENT` 和秒杀 reservation recovery 未验证。
