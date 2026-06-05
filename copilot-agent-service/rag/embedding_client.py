"""
Embedding Client — 通过 HTTP 调用 embedding-service。

Agent Service 不直接 import sentence-transformers，只通过此客户端通信。
模型名、服务地址均来自环境变量，切换模型只需改 .env。
"""
import time
import logging
import httpx
from typing import Sequence

from rag.config import rag_config

log = logging.getLogger(__name__)

_FALLBACK_DIM = 768


def embed_texts(texts: Sequence[str], normalize: bool = True) -> list[list[float]]:
    """
    调用 embedding-service，返回向量列表。

    失败时降级为零向量（开发调试用），并记录 warning。
    """
    url = f"{rag_config.embedding_service_url}/embed"
    try:
        # trust_env=False：禁用 HTTP_PROXY 等环境代理，模型服务是内部调用不走代理
        with httpx.Client(trust_env=False, timeout=rag_config.timeout_seconds) as client:
            resp = client.post(url, json={"texts": list(texts), "normalize": normalize})
        resp.raise_for_status()
        data = resp.json()
        return data["embeddings"]
    except Exception as e:
        log.warning("embedding_service_error", extra={"error": str(e), "url": url})
        dim = rag_config.embedding_dimension or _FALLBACK_DIM
        return [[0.0] * dim for _ in texts]


def embed_query(query: str) -> list[float]:
    """将用户 query 向量化（加 e5 query: 前缀）。"""
    prefixed = f"query: {query}"
    return embed_texts([prefixed])[0]


def embed_document(text: str) -> list[float]:
    """将文档文本向量化（加 e5 passage: 前缀）。"""
    prefixed = f"passage: {text}"
    return embed_texts([prefixed])[0]


def embed_documents_batch(texts: list[str]) -> list[list[float]]:
    """批量文档向量化（passage: 前缀）。"""
    prefixed = [f"passage: {t}" for t in texts]
    return embed_texts(prefixed)


# 维度常量（对齐配置，供 Milvus schema 使用）
EMBEDDING_DIM = rag_config.embedding_dimension
