# Embedding Service

本地 Embedding 微服务，供 Agent Service 通过 HTTP 调用，Agent 不直接 import 模型。

**默认模型**：`intfloat/multilingual-e5-base`（768 维，多语言，适合中文）

## 快速启动

```bash
# 本地开发（需先 pip install -r requirements.txt）
uvicorn app:app --host 0.0.0.0 --port 8100 --reload

# Docker（通过 Docker Compose 启动，见 infra/docker-compose.dev.yml）
docker compose -f infra/docker-compose.dev.yml --profile rag up -d embedding-service
```

## 接口

### GET /health
```json
{"status": "ok", "model": "intfloat/multilingual-e5-base", "device": "cuda", "dimension": 768}
```

### POST /embed
```json
{
  "texts": [
    "query: 我今天卖了多少钱？",
    "passage: 商家可以通过销售统计工具查询今日销售额"
  ],
  "normalize": true
}
```

> **e5 前缀规则**：用户 query 加 `query: `，知识库文档加 `passage: `

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `EMBEDDING_MODEL_NAME` | `intfloat/multilingual-e5-base` | 模型名 |
| `EMBEDDING_DIMENSION` | `768` | 维度（需与 Milvus collection 一致） |
| `EMBEDDING_DEVICE` | `auto` | `auto`/`cuda`/`cpu` |
| `MODEL_CACHE_DIR` | `/models` | 模型缓存目录 |
| `MAX_BATCH_SIZE` | `64` | 单次最多文本数 |
| `MAX_TEXT_LENGTH` | `2048` | 单条文本最大字符数 |
| `EMBEDDING_NORMALIZE` | `true` | 是否 L2 归一化 |

## 切换模型

只改环境变量，不改 Agent 代码：

```bash
# 切换到 bge-m3（注意：维度从 768 → 1024，需新建 Milvus collection）
EMBEDDING_MODEL_NAME=BAAI/bge-m3
EMBEDDING_DIMENSION=1024
MILVUS_COLLECTION=local_life_kb_1024
```
