# WSL 全栈部署交接文档

> 创建：2026-06-04 | 最后更新：2026-06-04 部署完成 | 作者：Claude
>
> **状态：✅ 全栈跑通。** 10 个 Docker 中间件 + 5 个应用服务全部健康，三条业务链路（REST API / MCP 工具 / 完整 RAG 检索）实测通过。
>
> 本文档记录：最终运行状态、完整启动步骤、本次部署踩过的全部坑及修复方案、下一次接手指南。

---

## 〇、一句话现状

所有基础设施和服务已在 WSL 本地跑通并验证。**唯一待补**：Agent Service 的 LLM API Key（DeepSeek 或 Claude），填入 `copilot-agent-service/.env` 后即可跑 `/chat` 端到端对话；其余全部可用。

---

## 一、最终环境

| 项目 | 状态 | 详情 |
|------|------|------|
| OS | ✅ | Ubuntu 22.04.4 LTS on WSL2 |
| 内核 | ✅ | 6.6.114.1-microsoft-standard-WSL2 |
| 项目路径 | ✅ | `/home/fengting/projects/local-life`（Linux 文件系统，非 /mnt）|
| Git 分支 / 远程 | ✅ | `main` / `https://github.com/fengting124/local-life-engineering-lab.git` |
| Java | ✅ | OpenJDK 17.0.19 |
| Maven | ✅ | Apache Maven 3.6.3 |
| Python | ✅ | 3.10.12（系统）；Agent 用 venv `copilot-agent-service/.venv` |
| Docker | ✅ | 28.1.1 + Compose v2.35.1（WSL Integration 已启用）|
| NVIDIA GPU | ✅ | RTX 4060 Laptop 8GB（**模型当前跑 CPU**，见第六节）|
| WSL 内存 | ⚠️ | 总 7.4GB（物理机约 16GB，WSL2 默认取一半），偏紧，影响 RocketMQ |
| 代理 | ✅ | `http://127.0.0.1:9674`（仅宿主机用；容器内/内部调用一律不走代理）|

---

## 二、服务清单与端口

### Docker 中间件（10 个，均 running）

| 容器 | 镜像 | 端口 | profile | 作用 |
|------|------|------|---------|------|
| local-life-mysql | mysql:8.4 | 3306 | （默认）| 主库 + 分片表 |
| local-life-redis | redis:7.4-alpine | 6379 | （默认）| 缓存/限流 |
| local-life-es | elasticsearch:8.17.3 | 9200 | search | 商铺搜索（含 IK 中文分词）|
| local-life-etcd | etcd:v3.5.16 | - | rag | Milvus 元数据 |
| local-life-minio | minio | - | rag | Milvus 对象存储 |
| local-life-milvus | milvus:v2.4.16 | 19530 / 9091 | rag | 向量库 |
| local-life-rmq-namesrv | rocketmq:5.3.2 | 9876 | mq | MQ 注册中心 |
| local-life-rmq-broker | rocketmq:5.3.2 | 10911 | mq | MQ broker（限堆 512MB）|
| local-life-embedding | local-life-embedding:latest | 8100 | rag | 本地 Embedding 服务 |
| local-life-reranker | local-life-reranker:latest | 8101 | rag | 本地 Reranker 服务 |

> 注：`attu`（Milvus Web UI）镜像 `zilliz/attu:v2.4.10` 拉取失败（registry 不存在该 tag），已跳过，不影响功能。

### 应用服务（5 个，均健康）

| 服务 | 端口 | 启动方式 | 健康检查 |
|------|------|----------|----------|
| Embedding Service | 8100 | Docker（rag profile）| `curl localhost:8100/health` → ok |
| Reranker Service | 8101 | Docker（rag profile）| `curl localhost:8101/health` → ok |
| LocalLife Server | 8080 | `mvn spring-boot:run` | `curl localhost:8080/actuator/health` → UP |
| MCP Server | 8081 | `mvn spring-boot:run` | `curl localhost:8081/actuator/health` → UP |
| Agent Service | 8000 | `uvicorn`（venv）| `curl localhost:8000/health` → ok |

---

## 三、完整启动步骤（从零冷启动）

> ⚠️ 所有 `curl` 验证都要加 `--noproxy '*'`，否则 WSL 代理会把 localhost 请求转发到代理导致 502。

### 1. 启动全部 Docker 中间件

```bash
cd ~/projects/local-life/infra

# 基础中间件
docker compose -f docker-compose.dev.yml up -d mysql redis

# 搜索（ES，含 IK 插件，见第五节坑4）
docker compose -f docker-compose.dev.yml --profile search up -d

# 消息队列（已限制 JVM 堆，见坑8）
docker compose -f docker-compose.dev.yml --profile mq up -d

# RAG：Milvus 栈 + 本地模型服务
docker compose -f docker-compose.dev.yml --profile rag up -d etcd minio milvus embedding-service reranker-service
```

模型服务首次启动会从 huggingface.co 下载模型（约 2GB，存入 `model-cache` volume），加载约 170s。后续从 volume 秒级加载。

### 2. 初始化数据库（仅首次）

```bash
cd ~/projects/local-life/local-life-server/src/main/resources/db/migration
for f in $(ls V*.sql | sort -V); do
  docker exec -i local-life-mysql mysql -uroot -p123456 local_life < "$f"
done
```

### 3. 启动 LocalLife Server（Java 8080）

```bash
cd ~/projects/local-life/local-life-server
JAVA_TOOL_OPTIONS="-Dspring.data.redis.host=localhost -Dspring.elasticsearch.uris=http://localhost:9200 -Drocketmq.name-server=localhost:9876 -Dinternal.api-key=local-life-internal-secret" \
  mvn spring-boot:run
# MySQL 连接：sharding.yaml 本地硬编码 localhost:3306，无需额外传参（见坑5）
```

### 4. 启动 MCP Server（Java 8081）

```bash
cd ~/projects/local-life/local-life-copilot
mvn spring-boot:run   # 配置默认全指向 localhost，无需传参
```

### 5. 启动 Agent Service（Python 8000）

```bash
cd ~/projects/local-life/copilot-agent-service
# 首次需建 venv 装依赖：
#   python3 -m venv .venv
#   .venv/bin/pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
#   .venv/bin/pip install "marshmallow<4" -i https://pypi.tuna.tsinghua.edu.cn/simple   ← 见坑7
cp .env.example .env   # 首次：填入 DEEPSEEK_API_KEY 或 ANTHROPIC_API_KEY
set -a && source .env && set +a
.venv/bin/uvicorn main:app --host 0.0.0.0 --port 8000
# 启动时自动 ingest 知识库到 Milvus（chunks=10），并校验 collection 维度
```

### 6. 知识库手动入库（可选，Agent 启动时会自动做）

```bash
cd ~/projects/local-life/copilot-agent-service
set -a && source .env && set +a
env -u http_proxy -u https_proxy -u all_proxy -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY \
  .venv/bin/python -m rag.ingest   # 必须清代理，gRPC 才能连 Milvus（见坑6）
```

---

## 四、验证命令（实测通过）

```bash
# 模型服务
curl --noproxy '*' http://localhost:8100/health   # {"status":"ok","model":"intfloat/multilingual-e5-base","dimension":768,"device":"cpu"}
curl --noproxy '*' http://localhost:8101/health   # {"status":"ok","model":"BAAI/bge-reranker-base","device":"cpu"}

curl --noproxy '*' -X POST http://localhost:8100/embed -H "Content-Type: application/json" \
  -d '{"texts":["query: 我今天卖了多少钱？"],"normalize":true}'   # 返回 768 维向量

curl --noproxy '*' -X POST http://localhost:8101/rerank -H "Content-Type: application/json" \
  -d '{"query":"我今天卖了多少钱？","documents":[{"id":"1","text":"商家可查询今日销售额"},{"id":"2","text":"优惠券核销"}],"top_k":1}'

# Java 服务
curl --noproxy '*' http://localhost:8080/actuator/health   # db/redis/elasticsearch 全 UP
curl --noproxy '*' http://localhost:8081/actuator/health   # UP

# MCP 工具（9 个）：列表 + 实调
curl --noproxy '*' -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" -H "X-User-Id: 10001" -H "X-User-Role: merchant" -H "X-Merchant-Id: 20001" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}'
curl --noproxy '*' -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" -H "X-User-Id: 10001" -H "X-User-Role: merchant" -H "X-Merchant-Id: 20001" \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"shop_metrics_query","arguments":{"shop_id":1,"date":"today"}}}'

# Agent Service
curl --noproxy '*' http://localhost:8000/health

# RAG 客户端连通性（embedding + reranker）
cd ~/projects/local-life/copilot-agent-service && set -a && source .env && set +a
.venv/bin/python scripts/test_rag_clients.py    # → ✅ RAG 客户端全部连通

# 完整 RAG 检索链路（embedding → Milvus 召回 → reranker 精排）
env -u http_proxy -u https_proxy -u all_proxy -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY .venv/bin/python -c "
import asyncio; from rag.pipeline import retrieve
r = asyncio.run(retrieve('优惠券怎么核销', merchant_id=20001, top_k=3))
print('refused=', r.refused, 'docs=', len(r.docs))"
# 实测：命中"LocalLife 平台规则手册" top_score=0.862
```

**最近一次体检结果（2026-06-04 21:06）：**
- 10 个 Docker 中间件全部 running
- 5 个应用服务全部 ok/UP
- LocalLife Server 组件：db=UP redis=UP elasticsearch=UP
- MCP 工具数：9，实调 shop_metrics_query 返回真实数据
- RAG：向量召回 10 条 → reranker 精排 → top_score=0.862，未拒答
- Milvus collection `local_life_kb`：768 维，row_count=30

---

## 五、本次部署踩过的全部坑及修复（重要！下次直接照做）

### 坑 1：Docker 构建 pip 全部失败（ProxyError）
**现象**：`docker compose build` 时 pip 报 `ProxyError: Cannot connect to proxy ... 127.0.0.1:9674 Connection refused`。
**根因**：① 容器内 `127.0.0.1` 指容器自身不是宿主机；② Docker Desktop 在 daemon 级注入了 `http.docker.internal:3128` 代理。
**修复**：在 Docker Desktop → Settings → Resources → Proxies 关闭手动代理；Dockerfile 里 pip 用清华源 `-i https://pypi.tuna.tsinghua.edu.cn/simple`，torch 用 `--extra-index-url https://download.pytorch.org/whl/cpu`。
**关键认知**：`docker run` 不继承 daemon 代理，但 `docker build`（buildkit）会受影响。直接 `docker build` 测试能区分是 compose 缓存还是网络问题。

### 坑 2：镜像源不稳定（JSONDecodeError / hash mismatch）
**现象**：清华源偶发 `json.decoder.JSONDecodeError: Unterminated string`；阿里源 `uvloop` 包 `THESE PACKAGES DO NOT MATCH THE HASHES`。
**修复**：用清华源 + `--retries 5 --timeout 120`；torch 单独走 pytorch.org 官方 whl（hash 一定正确）。

### 坑 3：容器内模型下载失败（huggingface_hub bug）
**现象**：设了 `HF_ENDPOINT=https://hf-mirror.com` 后报 `FileMetadataError: Distant resource does not seem to be on huggingface.co`。
**根因**：huggingface_hub 0.36 对 hf-mirror 响应头做严格校验，误判。
**修复**：compose 里 **不设** HF_ENDPOINT（设为空字符串覆盖 Dockerfile 里的 ENV），容器直连 huggingface.co（WSL 网络可达）。同时清空容器内所有代理环境变量。

### 坑 4：Elasticsearch 缺 IK 中文分词器
**现象**：LocalLife Server 启动报 `Custom Analyzer [ik_max_word_analyzer] failed to find tokenizer under name [ik_max_word]`。
**修复（已固化）**：做成自定义镜像 `infra/elasticsearch/Dockerfile`（`FROM ...es:8.17.3` + `RUN elasticsearch-plugin install analysis-ik`），compose 的 elasticsearch 服务改为 `build`。首次 `--profile search up -d` 自动构建，`down -v` 后无需手动重装。
> 临时手动方式（应急）：`docker exec local-life-es bin/elasticsearch-plugin install --batch https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.17.3.zip && docker restart local-life-es`

### 坑 5：ShardingSphere 配置两连坑
**坑 5a**：`${MYSQL_HOST:-localhost}` 这种 bash 默认值语法 ShardingSphere 5.5.0 解析失败 → `NumberFormatException: For input string: "-localhost}"`。
**修复**：本地开发版 `sharding.yaml` 直接硬编码 `localhost:3306/root/123456`；Docker 版用 `sharding-docker.yaml`（`${MYSQL_HOST}` 无默认值，靠 JAVA_OPTS 注入）。compose 里 `SPRING_DATASOURCE_URL` 指向 `sharding-docker.yaml`。

**坑 5b**：改完仍报 `Algorithm 'MOD' ... can not use auto sharding algorithm`。
**根因**：`MOD`/`HASH_MOD` 是 **auto-sharding 算法**，只能配 `autoTables`，不能用于 `standard` 手动表策略（`actualDataNodes`）。
**修复**：算法改为 `INLINE`，表达式 `order_info_${user_id % 4}`。文件：`local-life-server/src/main/resources/sharding.yaml`。
> 参考：apache/shardingsphere issues #29253 #31964

### 坑 6：pymilvus gRPC 走代理连不上 Milvus
**现象**：`MilvusException: Fail connecting to server on localhost:19530`，但 `nc -zv localhost 19530` 通。
**根因**：gRPC 读取 `ALL_PROXY`/`http_proxy` 环境变量，把 localhost 也走代理。
**修复**：① `rag/vector_store.py` 模块加载时把 milvus_host + localhost + 127.0.0.1 写入 `no_proxy`/`NO_PROXY`；② 命令行手动跑 ingest 时 `env -u http_proxy -u https_proxy -u all_proxy ...`。

### 坑 7：marshmallow 版本冲突
**现象**：`module 'marshmallow' has no attribute '__version_info__'`，导致 pymilvus 整个不可用、Milvus 写入 skip。
**根因**：marshmallow 4.x 与 pymilvus 2.4.9 依赖的 environs 不兼容。
**修复**：`pip install "marshmallow<4"`（降到 3.26.2）。
> ⚠️ 应固化到 requirements.txt，避免重装时再踩。

### 坑 8：RocketMQ broker OOM（exit 137）
**现象**：broker 启动后立刻 `Killed` 退出，码 137。
**根因**：RocketMQ broker 默认 JVM 堆申请 8GB，WSL 总内存仅 7.4GB。
**修复**：compose 里给 broker 加 `JAVA_OPT_EXT: "-server -Xms512m -Xmx512m -Xmn256m"`，namesrv 加 256m。

### 坑 9：Agent Service httpx 内部调用走代理（502）
**现象**：`mcp_server_unhealthy status=502`；RAG 客户端 embedding 返回全零向量。
**根因**：httpx 默认 `trust_env=True`，读取 WSL 代理环境变量。
**修复**：所有调用内部服务的 httpx 客户端加 `trust_env=False`。文件：`rag/embedding_client.py`、`rag/reranker_client.py`、`mcp/mcp_client.py`、`main.py`。

### 坑 10：Agent ingest 用本地模型 + Pydantic 拒绝额外字段
**坑 10a**：`pipeline.py` 的 ingest 用 `from rag.embedding import embed_texts`（直接本地加载 sentence-transformers），违反"模型解耦"原则且重复加载。
**修复**：改用 `from rag.embedding_client import embed_documents_batch`（走 HTTP）。

**坑 10b**：`.env` 里新增的 RAG 字段导致 `pydantic ... Extra inputs are not permitted`。
**修复**：`config/settings.py` 的 `Config` 加 `extra = "ignore"`（RAG 配置由 `rag/config.py` 单独读）。

### 坑 11：Milvus 与 etcd lease 失效退出
**现象**：Milvus 运行一段时间后 `Exited (1)`，日志 `Proxy disconnected from etcd, process will exit`。
**修复**：`docker start local-life-milvus` 重启即可（etcd 重启或 lease 过期偶发）。

---

## 六、模型与 GPU 说明

- **当前模型跑在 CPU**（`device=cpu`）。embedding ~237ms/次，reranker ~226ms/次，可接受。
- 有 RTX 4060 8GB GPU，但容器内走 GPU 需要 NVIDIA Container Toolkit，当前未配置。
- 如需启用 GPU：装 nvidia-container-toolkit + compose 加 `deploy.resources.reservations.devices`，并把 `EMBEDDING_DEVICE`/`RERANKER_DEVICE` 设为 `cuda`。CPU fallback 已验证可用，不强制。

### 模型选型（详见 `docs/01-project/RAG模型选型说明.md`）

| 用途 | 模型 | 维度 |
|------|------|------|
| Embedding（默认）| intfloat/multilingual-e5-base | 768 |
| Reranker（默认）| BAAI/bge-reranker-base | - |

### 模型替换（只改 .env，不改代码）

```bash
# 切 bge-m3（注意 1024 维，必须新建 collection）
EMBEDDING_MODEL_NAME=BAAI/bge-m3
EMBEDDING_DIMENSION=1024
MILVUS_COLLECTION=local_life_kb_1024
# 重启 embedding-service + 重新 ingest
```
维度保护：`rag/vector_store.py` 会在连接/写入时校验维度，不一致抛 `DimensionMismatchError` 并提示新建 collection。

---

## 七、仍需用户处理的事项

1. **必填**：Agent Service 的 LLM API Key → `copilot-agent-service/.env` 的 `DEEPSEEK_API_KEY` 或 `ANTHROPIC_API_KEY`。填完重启 Agent 即可跑 `/chat`。
2. ~~建议固化到代码~~ **已固化**：
   - ✅ `requirements.txt` 已加 `marshmallow<4`
   - ✅ ES 的 IK 插件已做成自定义镜像 `infra/elasticsearch/Dockerfile`，compose 改为 build（首次 `--profile search up -d` 会自动构建，`down -v` 后也无需手动重装）
3. **可选**：WSL 内存偏紧（7.4GB），可在 Windows `C:\Users\<你>\.wslconfig` 设 `[wsl2]` + `memory=12GB` 后 `wsl --shutdown`。

---

## 八、下次接手快速恢复

```bash
# 0. 确认路径和 Docker
cd ~/projects/local-life && docker ps

# 1. 若容器已停，重启全部中间件
cd infra
docker compose -f docker-compose.dev.yml up -d mysql redis
docker compose -f docker-compose.dev.yml --profile search up -d
docker compose -f docker-compose.dev.yml --profile mq up -d
docker compose -f docker-compose.dev.yml --profile rag up -d etcd minio milvus embedding-service reranker-service

# 2. 等模型服务就绪（从 volume 加载，约 30s）
until curl --noproxy '*' -sf localhost:8100/health >/dev/null; do sleep 3; done
until curl --noproxy '*' -sf localhost:8101/health >/dev/null; do sleep 3; done

# 3. 若 ES 是新建容器，重装 IK 插件（见坑4）

# 4. 启动 3 个应用服务（见第三节 3/4/5）

# 5. 体检（见第四节）
```

**注意**：数据库迁移、知识库 ingest 只需首次做；MySQL/Milvus 数据持久化在 Docker volume，重启容器不丢。

---

## 九、本次部署改动的文件清单

**新增：**
- `copilot-agent-service/model_services/embedding_service/`（app.py, Dockerfile, requirements.txt, .env.example, README.md）
- `copilot-agent-service/model_services/reranker_service/`（同上结构）
- `copilot-agent-service/rag/`：config.py, schemas.py, embedding_client.py, reranker_client.py
- `copilot-agent-service/scripts/test_rag_clients.py`
- `local-life-server/src/main/resources/sharding-docker.yaml`
- `infra/elasticsearch/Dockerfile`（内置 IK 插件的自定义 ES 镜像）
- `infra/scripts/`：check-wsl.sh, check-rag.sh, start-local.sh, stop-local.sh
- `docs/`：本文件 + RAG模型选型说明.md + 模型解耦设计.md + WSL本地开发指南.md

**修改：**
- `infra/docker-compose.dev.yml`：rag profile 加 embedding/reranker + model-cache volume；healthcheck 改 python urllib；代理环境变量清空；RocketMQ 限堆；server 用 sharding-docker.yaml
- `infra/.env.example` / `infra/.env`：补 RAG、模型变量；代理清空
- `local-life-server/src/main/resources/sharding.yaml`：硬编码 localhost + INLINE 算法
- `copilot-agent-service/.env.example`：LLM 默认 DeepSeek + RAG 变量
- `copilot-agent-service/config/settings.py`：DeepSeek 字段 + `extra="ignore"`
- `copilot-agent-service/rag/`：pipeline.py（ingest 走 HTTP）、vector_store.py（维度保护 + no_proxy）
- `copilot-agent-service/mcp/mcp_client.py`、`main.py`：httpx `trust_env=False`
- `.gitignore`：补 .env.*、模型缓存、venv

**已固化（不再需手动）：** `requirements.txt` 加 `marshmallow<4`；ES IK 插件做成 `infra/elasticsearch/Dockerfile`

---

## 十、建议 commit message

```
feat: add decoupled local embedding/reranker services and get full stack running on WSL

- Add embedding-service (e5-base 768d, 8100) and reranker-service (bge-reranker-base, 8101)
- Wire both into docker-compose rag profile with shared model-cache volume, CPU fallback
- Refactor Agent RAG to call model services via HTTP (embedding_client/reranker_client), no direct ML import
- Add Milvus dimension protection + no_proxy handling for gRPC
- Fix ShardingSphere: hardcode local dsn + switch MOD→INLINE algorithm (auto-sharding incompat)
- Disable proxy (trust_env=False) on all internal httpx calls
- Limit RocketMQ JVM heap to fit WSL memory
- Settings: default DeepSeek provider, extra=ignore
- Add WSL/RAG check & start/stop scripts and deployment docs
```
