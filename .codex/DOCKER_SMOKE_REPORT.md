# Docker Lite Smoke Test Report

## 基本信息
- 分支：feature/engineering-hardening（本地验证分支；从 main@46281c1 创建）
- 提交 SHA：46281c1（本报告记录的是该 SHA 之后的本地修复工作区）
- 操作系统：WSL Ubuntu 22.04 on Docker Desktop
- Docker 版本：28.1.1
- Docker Compose 版本：v2.35.1-desktop.1
- 执行时间：2026-07-18 00:02-01:15 Asia/Shanghai

## 实际启动命令
- Compose 文件组合：`-f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app`
- config 命令：`docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app config`
- pull 命令：`docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app pull`
- full build 命令：`DOCKER_BUILDKIT=1 docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app build --pull locallife-server`
- runtime rebuild 命令：`mvn -B -pl local-life-server -DskipTests package && docker build -f local-life-server/Dockerfile.runtime -t locallife-server:latest .`
- up 命令：`docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.lite.yml --profile app up -d locallife-server`

## 镜像结果
| 镜像/服务 | 拉取 | 构建 | 说明 |
| --- | --- | --- | --- |
| mysql | PASS | N/A | `mysql:8.4` 可启动并 healthy。 |
| redis | PASS | N/A | `redis:7.4-alpine` 可启动并 healthy。 |
| locallife-server | N/A | PASS/PARTIAL | full Dockerfile build 成功一次，耗时 20:52；后续 Lite profile 修复后用 runtime Dockerfile 快速重打最新 jar 验证启动。 |
| locallife-copilot | N/A | NOT REBUILT | 现有镜像容器 healthy；本轮未重新构建。 |
| copilot-agent | N/A | NOT REBUILT | 现有镜像容器 healthy；本轮未重新构建。 |

## 容器结果
| 服务 | 状态 | Health | 端口 | 说明 |
| --- | --- | --- | --- | --- |
| mysql | Up | healthy | 3306 | 基础依赖正常。 |
| redis | Up | healthy | 6379 | 基础依赖正常。 |
| local-life-server | Up | healthy | 8080 | Lite profile 启动成功，`/actuator/health` 返回 200/UP。 |
| local-life-copilot | Up | healthy | 8081 | `/actuator/health` 返回 UP。 |
| copilot-agent-service | Up | healthy | 8000 | `/health` 返回 OK，`/openapi.json` 返回 200。 |
| embedding/reranker | Up | healthy | 8100/8101 | 现有模型服务 healthy。 |

## 烟雾测试
| 测试项 | 结果 | 证据 |
| --- | --- | --- |
| Compose config 解析 | PASS | `docker compose ... --profile app config` 成功。 |
| 服务名通信检查 | PASS | server 使用 `mysql`、`redis`，copilot 使用 `locallife-server`，agent 使用 `locallife-copilot`。 |
| MySQL 健康 | PASS | `local-life-mysql` healthy。 |
| Redis 健康 | PASS | `local-life-redis` healthy。 |
| Java Server 健康 | PASS | `curl http://localhost:8080/actuator/health` 返回 200/UP，db/redis 均 UP。 |
| Java Server OpenAPI | PASS | `curl http://localhost:8080/v3/api-docs` 返回 OpenAPI JSON。 |
| Copilot 健康 | PASS | `curl http://localhost:8081/actuator/health` 返回 UP。 |
| Agent 健康 | PASS | `curl http://localhost:8000/health` 返回 `{"status":"ok"}`。 |
| Agent OpenAPI | PASS | `curl http://localhost:8000/openapi.json` 返回 HTTP 200。 |
| 幂等下单/支付/Outbox | NOT RUN | 本轮先完成容器启动阻塞修复，未构造业务写入烟雾数据。 |

## Milvus Lite 持久化
- URI：`/app/data/local_life_kb.db`（Compose Lite override）
- 宿主机路径：Docker named volume `infra_agent_rag_data`
- 容器路径：`/app/data`
- 重启前检索结果：未执行
- 重启后检索结果：未执行
- 结论：未验证。本轮重点修复 Java Lite 启动阻塞，未重新构建 agent 镜像和导入 RAG 测试文档。

## 数据库迁移
- 已执行版本：未在本轮重新执行 db-init；当前 MySQL volume 已存在业务表，server 可连接并通过 health。
- V11 结果：未单独验证
- V12 结果：未单独验证
- V13 结果：未单独验证
- 是否可重复执行：未验证

## 发现的问题
| 严重级别 | 问题 | 根因 | 修复状态 |
| --- | --- | --- | --- |
| High | Lite 环境下 `local-life-server` 启动失败 | Lite 不启动 Elasticsearch，但 server 默认搜索实现、ES 配置和 ES health indicator 仍加载。 | 已修复：新增 `lite` profile、搜索 fallback、禁用 ES config/scheduling/health。 |
| High | Lite 环境下 RocketMQ 消费者导致启动失败 | Lite 不启动 RocketMQ，但 3 个 `@RocketMQMessageListener` 消费者仍注册并连接 nameserver。 | 已修复：消费者加 `@Profile("!test & !lite")`。 |
| Medium | Java Docker build 可观测性差 | Dockerfile Maven 命令使用 `-q`，长时间无输出。 | 已修复：两个 Java Dockerfile 改为 `-ntp`。 |
| Medium | Java full Dockerfile build 很慢 | Docker 内首次 Maven 依赖收集非常慢，server full build 约 20:52。 | 部分缓解：日志可见；后续建议引入 BuildKit Maven cache 或 CI 预热策略。 |
| Medium | `docker compose pull` 对本地镜像返回 pull denied | `locallife-server`、`locallife-copilot`、`copilot-agent` 是本地 build 镜像，不在远端 registry。 | 可接受，记录为 N/A。 |

## 修改文件
- `infra/docker-compose.lite.yml`：为 server 启用 `spring.profiles.active=lite`。
- `local-life-server/src/main/resources/application-lite.yml`：Lite profile 禁用 ES health、tracing、scheduling、Canal，并排除 ES 自动配置。
- `local-life-server/src/main/java/.../search/service/*`：抽出搜索接口并提供 Lite fallback。
- `local-life-server/src/main/java/.../config/ElasticsearchConfig.java`：排除 lite profile。
- `local-life-server/src/main/java/.../config/SchedulingConfig.java`：排除 lite profile。
- `local-life-server/src/main/java/.../mq/consumer/*Consumer.java`：排除 lite profile。
- `local-life-server/src/test/java/.../LiteProfileConfigTest.java`：锁定 Lite profile 外部依赖屏蔽。
- `local-life-server/src/test/java/.../LiteSearchFallbackServiceTest.java`：锁定 Lite 搜索降级行为。
- `local-life-server/Dockerfile`、`local-life-copilot/Dockerfile`：Maven build 输出从 `-q` 调整为 `-ntp`。

## 验证
- `mvn -B -pl local-life-server -Dtest=LiteProfileConfigTest,LiteSearchFallbackServiceTest test`：PASS，6 tests。
- `mvn -B -pl local-life-server test`：PASS，182 tests。
- `DOCKER_BUILDKIT=1 docker compose ... build --pull locallife-server`：PASS 一次，server full Dockerfile build 成功。
- `mvn -B -pl local-life-server -DskipTests package && docker build -f local-life-server/Dockerfile.runtime -t locallife-server:latest .`：PASS，用于快速产出包含最终修复的 runtime 镜像。
- `docker compose ... up -d locallife-server`：PASS，server healthy。
- `curl http://localhost:8080/actuator/health`：PASS，HTTP 200/UP。
- `curl http://localhost:8081/actuator/health`：PASS，HTTP 200/UP。
- `curl http://localhost:8000/health`：PASS，HTTP 200/OK。

## 最终结论
PARTIAL：Lite 环境的 Java server 启动阻塞已修复，server/copilot/agent 现均 healthy，核心健康检查和 OpenAPI 可访问；但本轮未重新构建 copilot/agent 镜像，未执行数据库迁移幂等复验、Milvus Lite 持久化复验和业务写入端到端烟雾测试。
