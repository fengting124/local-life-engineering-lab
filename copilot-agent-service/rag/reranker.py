"""
Reranker：从 Top 20 精排到 Top 5。

为什么需要 Reranker？
  向量搜索（ANN）用 embedding 相似度排序，
  相似度高不等于「更能回答当前问题」。
  Cross-Encoder Reranker 对查询+文档联合打分，精度显著高于 Bi-Encoder。

性能数据（典型）：
  Bi-Encoder 召回 Top 20 → Reranker 精排 → Top 5 传给 LLM
  Recall@5（Bi-Encoder 直接取 5）：~0.65
  Recall@5（Reranker 精排后取 5）：~0.82
  → 提升约 26%

选型：
  推荐 cross-encoder/ms-marco-MiniLM-L-6-v2（中英文适用，速度快）
  或 BAAI/bge-reranker-v2-m3（中文效果更好）

  API 方案（无本地 GPU 时）：
  - Cohere Rerank API（$0.10/1000 文档对）
  - 商业 API，成本可控

本实现：
  优先本地 cross-encoder，失败时 fallback 到按 score 降序（即不 rerank）。

检索分数阈值（RELEVANCE_THRESHOLD）：
  低于 0.3 的结果被过滤，避免把不相关内容送给 LLM。
  当所有结果都低于阈值时，返回「无相关文档」并拒答。
  设计文档 7.4：「检索分数低于阈值时拒答，避免编造规则」
"""
import structlog

log = structlog.get_logger(__name__)

# Reranker 配置
RERANKER_MODEL     = "cross-encoder/ms-marco-MiniLM-L-6-v2"
TOP_K_FINAL        = 5       # 精排后取 top 5 传给 LLM
RELEVANCE_THRESHOLD = 0.3    # 低于此分数的文档被过滤（拒答阈值）

_reranker = None


def _get_reranker():
    """懒加载 reranker（单例）。"""
    global _reranker
    if _reranker is None:
        try:
            from sentence_transformers import CrossEncoder
            _reranker = CrossEncoder(RERANKER_MODEL)
            log.info("reranker_loaded", model=RERANKER_MODEL)
        except ImportError:
            log.warning("reranker_not_available", hint="pip install sentence-transformers")
    return _reranker


def rerank(query: str, candidates: list[dict]) -> list[dict]:
    """
    对候选文档列表做 Cross-Encoder 精排。

    :param query:      用户原始查询
    :param candidates: 向量搜索返回的候选列表（每个含 content / score 字段）
    :return: 精排并过滤后的文档列表（按 rerank_score 降序，最多 TOP_K_FINAL 条）

    返回空列表表示「无相关文档」，调用方应拒答而不是编造内容。
    """
    if not candidates:
        return []

    reranker = _get_reranker()

    if reranker is None:
        # Fallback：按原始向量相似度排序，取前 TOP_K_FINAL 条
        sorted_candidates = sorted(candidates, key=lambda x: x.get("score", 0), reverse=True)
        top = sorted_candidates[:TOP_K_FINAL]
        return [c for c in top if c.get("score", 0) >= RELEVANCE_THRESHOLD]

    # Cross-Encoder 打分：pairs = [(query, content), (query, content), ...]
    pairs  = [(query, c["content"]) for c in candidates]
    scores = reranker.predict(pairs)

    # 附加 rerank_score
    scored = [
        {**c, "rerank_score": float(s)}
        for c, s in zip(candidates, scores)
    ]

    # 过滤低于阈值的文档
    filtered = [d for d in scored if d["rerank_score"] >= RELEVANCE_THRESHOLD]

    if not filtered:
        log.info("reranker_no_relevant", query=query[:50], threshold=RELEVANCE_THRESHOLD)
        return []

    # 按 rerank_score 降序，取前 TOP_K_FINAL
    sorted_docs = sorted(filtered, key=lambda x: x["rerank_score"], reverse=True)
    return sorted_docs[:TOP_K_FINAL]
