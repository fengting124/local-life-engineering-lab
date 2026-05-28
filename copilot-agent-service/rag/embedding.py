"""
文本 Embedding 模块。

选择 sentence-transformers 而不是 OpenAI/Anthropic Embedding API 的原因：
  1. 完全本地，零 API 成本，适合离线环境
  2. multilingual-e5-base 对中英文混合文本效果好（平台知识库以中文为主）
  3. 384/768 维向量足够，Milvus 存储压力小

备用方案（通过 settings.embedding_provider 切换）：
  - openai：text-embedding-3-small（成本低，效果更好）
  - anthropic：目前无官方 Embedding API，不支持

性能优化：
  - 模型单例（全局 load 一次）
  - 批量 encode（支持 batch 操作）
  - 向量归一化（与 Milvus 的 IP 距离度量兼容）
"""
import structlog
import numpy as np
from typing import Sequence

log = structlog.get_logger(__name__)

# 懒加载，避免 import 时就触发模型下载
_model = None
_model_name: str = "intfloat/multilingual-e5-base"   # 768 维，中英双语
_fallback_dim: int = 768


def _get_model():
    """懒加载 sentence-transformers 模型（单例）。"""
    global _model
    if _model is None:
        try:
            from sentence_transformers import SentenceTransformer
            log.info("embedding_model_loading", model=_model_name)
            _model = SentenceTransformer(_model_name)
            log.info("embedding_model_loaded", model=_model_name, dim=_fallback_dim)
        except ImportError:
            log.warning(
                "sentence_transformers_not_installed",
                hint="pip install sentence-transformers",
            )
    return _model


def embed_texts(texts: Sequence[str]) -> list[list[float]]:
    """
    将文本列表转换为向量列表。

    multilingual-e5 模型要求在输入前加前缀：
      - 查询文本：加 "query: " 前缀（用于检索查询）
      - 文档文本：加 "passage: " 前缀（用于索引文档）

    此函数自动识别并添加前缀（若未添加）。

    :param texts: 文本列表
    :return: 对应的向量列表（每个向量为 768 维 float 列表）
    """
    model = _get_model()
    if model is None:
        # 模型不可用时返回零向量（降级，用于开发调试）
        log.warning("embedding_fallback_zero_vectors", count=len(texts))
        return [[0.0] * _fallback_dim for _ in texts]

    # 批量编码，normalize 保证向量归一化（与 Milvus IP 距离兼容）
    embeddings = model.encode(
        list(texts),
        batch_size=32,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    return embeddings.tolist()


def embed_query(query: str) -> list[float]:
    """
    将查询文本转换为向量（加 'query: ' 前缀）。

    :param query: 用户查询文本
    :return: 768 维向量
    """
    prefixed = f"query: {query}"
    vectors = embed_texts([prefixed])
    return vectors[0]


def embed_document(text: str) -> list[float]:
    """
    将文档文本转换为向量（加 'passage: ' 前缀）。

    :param text: 文档文本片段
    :return: 768 维向量
    """
    prefixed = f"passage: {text}"
    vectors = embed_texts([prefixed])
    return vectors[0]


EMBEDDING_DIM = _fallback_dim
