# RAG 模型选型说明

> 时间：2026-06-04 | 版本：v1.0 | 作者：Claude

---

## 第一版默认模型

### Embedding 模型

**intfloat/multilingual-e5-base**

| 属性 | 值 |
|------|----|
| 维度 | 768 |
| 语言 | 多语言（含中文） |
| 大小 | ~1.1GB |
| 设备 | CUDA / CPU fallback |

**选择原因：**

1. 768 维，和当前项目原始设计一致，Milvus collection 无需重建。
2. 支持多语言，适合中文业务问答（商家、商品、订单等场景）。
3. 资源占用较低，适合 8GB 显存和 CPU fallback。
4. sentence-transformers 支持成熟，方便 Docker 化部署。
5. 第一版目标是稳定跑通、可维护、好解释——不追求最高精度。

**使用说明：**

e5 系列要求 query 和 passage 加前缀：
- 用户 query：`query: 我今天卖了多少钱？`
- 知识库文档：`passage: 商家可以通过销售统计工具查询今日销售额`

---

### Reranker 模型

**BAAI/bge-reranker-base**

| 属性 | 值 |
|------|----|
| 类型 | CrossEncoder |
| 语言 | 多语言（含中文） |
| 大小 | ~1.1GB |
| 设备 | CUDA / CPU fallback |

**选择原因：**

1. 适合 query-document pair 精排，精度高于双塔模型。
2. 支持多语言场景，覆盖中文业务问答。
3. 比 cross-encoder/ms-marco-MiniLM-L6-v2 更适合作为中文项目默认 reranker。
4. 工程复杂度低，适合本地服务化，部署和维护成本可控。

---

## 备用模型

### Fast Reranker（英文 / 低资源场景）

**cross-encoder/ms-marco-MiniLM-L6-v2**

**用途：**
1. 英文检索场景。
2. 低资源快速精排（模型更小，延迟更低）。
3. 作为 fallback，不作为默认中文业务模型。

---

### 增强版 Embedding

**BAAI/bge-m3**

| 属性 | 值 |
|------|----|
| 维度 | **1024**（注意！） |
| 语言 | 多语言 |
| 大小 | ~2.3GB |

> **⚠ 重要**：bge-m3 输出 **1024 维**。
> 如果从 multilingual-e5-base（768 维）切到 bge-m3，**不能复用旧 Milvus collection**。
> 必须新建 collection 或重建索引。
> bge-m3 可用于后续 hybrid retrieval（稠密 + 稀疏）升级。

---

## 实验性增强模型（后续阶段）

### Embedding

**Qwen/Qwen3-Embedding-0.6B**

### Reranker

**Qwen/Qwen3-Reranker-0.6B**

> **注意：**
> 1. 仅作为后续增强，不作为第一版默认。
> 2. 模型更大，部署复杂度更高。
> 3. 必须通过环境变量切换，不改业务 Agent 代码。

---

## 模型解耦原则

1. **Agent Service 不直接 import HuggingFace 模型**。
2. Agent Service 只通过 HTTP 调用 `embedding-service` 和 `reranker-service`。
3. 模型服务内部通过环境变量读取模型名：`EMBEDDING_MODEL_NAME`、`RERANKER_MODEL_NAME`。
4. Milvus collection 必须记录 `embedding_model`、`embedding_dimension`、`created_at`。
5. 如果 `EMBEDDING_DIMENSION` 改变，**必须拒绝写入旧 collection**，并提示创建新 collection。
6. `.env.example` 必须暴露以下变量：
   ```
   EMBEDDING_MODEL_NAME=intfloat/multilingual-e5-base
   EMBEDDING_DIMENSION=768
   RERANKER_MODEL_NAME=BAAI/bge-reranker-base
   RAG_TOP_K_RECALL=20
   RAG_TOP_K_RERANK=5
   RAG_MIN_SCORE=0.3
   ```
7. **后续替换模型时，只改环境变量和 collection 配置，不改业务 Agent 代码。**

---

## 模型替换步骤

### 场景：从 multilingual-e5-base（768 维）切换到 bge-m3（1024 维）

```bash
# 1. 修改 .env
EMBEDDING_MODEL_NAME=BAAI/bge-m3
EMBEDDING_DIMENSION=1024
MILVUS_COLLECTION=local_life_kb_1024   # 新建 collection

# 2. 重启 embedding-service
docker compose -f infra/docker-compose.dev.yml restart embedding-service

# 3. 重新入库
python -m rag.ingest

# 4. 验证
curl http://localhost:8100/health
# 确认 "dimension": 1024

# 5. Agent Service 自动读取新配置，无需修改代码
```

### 场景：切换 Reranker

```bash
# 修改 .env
RERANKER_MODEL_NAME=cross-encoder/ms-marco-MiniLM-L6-v2

# 重启 reranker-service
docker compose -f infra/docker-compose.dev.yml restart reranker-service

# 验证
curl http://localhost:8101/health
```

---

## 版本记录

| 版本 | 时间 | Embedding | Reranker | 备注 |
|------|------|-----------|----------|------|
| v1.0 | 2026-06-04 | multilingual-e5-base (768) | bge-reranker-base | 初始版本 |
