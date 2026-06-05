# Reranker Service

本地精排微服务，供 Agent Service 通过 HTTP 调用。

**默认模型**：`BAAI/bge-reranker-base`（CrossEncoder，多语言）

## 快速启动

```bash
uvicorn app:app --host 0.0.0.0 --port 8101 --reload

# Docker Compose
docker compose -f infra/docker-compose.dev.yml --profile rag up -d reranker-service
```

## 接口

### GET /health
```json
{"status": "ok", "model": "BAAI/bge-reranker-base", "device": "cuda"}
```

### POST /rerank
```json
{
  "query": "我今天卖了多少钱？",
  "documents": [
    {"id": "doc1", "text": "商家可以通过销售统计工具查询今日销售额"},
    {"id": "doc2", "text": "优惠券可以创建和核销"}
  ],
  "top_k": 1
}
```

返回按 score 降序排列。

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `RERANKER_MODEL_NAME` | `BAAI/bge-reranker-base` | 模型名 |
| `RERANKER_DEVICE` | `auto` | `auto`/`cuda`/`cpu` |
| `MODEL_CACHE_DIR` | `/models` | 模型缓存目录 |
| `MAX_DOCUMENTS` | `50` | 单次最多候选文档数 |
| `MAX_QUERY_LENGTH` | `512` | query 最大字符数 |
| `MAX_DOCUMENT_LENGTH` | `2048` | 单文档最大字符数 |

## 切换到英文快速精排模型

```bash
RERANKER_MODEL_NAME=cross-encoder/ms-marco-MiniLM-L6-v2
```
