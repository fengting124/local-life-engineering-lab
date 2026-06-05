"""
Reranker Client — 通过 HTTP 调用 reranker-service。

Agent Service 不直接 import sentence-transformers CrossEncoder，只通过此客户端通信。
"""
import logging
import httpx

from rag.config import rag_config

log = logging.getLogger(__name__)

TOP_K_FINAL        = rag_config.top_k_rerank
RELEVANCE_THRESHOLD = rag_config.min_score


def rerank(query: str, candidates: list[dict], top_k: int | None = None) -> list[dict]:
    """
    调用 reranker-service 对候选文档精排。

    失败时 fallback 到按向量相似度排序（不中断 RAG 流程）。

    :param query:      用户查询文本
    :param candidates: 候选文档列表（需含 chunk_id、content 字段）
    :param top_k:      精排后保留数量（None 时用 RAG_TOP_K_RERANK）
    :return: 精排过滤后的文档列表（含 rerank_score 字段，降序）
    """
    if not candidates:
        return []

    effective_top_k = top_k if top_k is not None else TOP_K_FINAL
    url = f"{rag_config.reranker_service_url}/rerank"

    try:
        docs_payload = [
            {"id": c.get("chunk_id", str(i)), "text": c.get("content", "")}
            for i, c in enumerate(candidates)
        ]
        # trust_env=False：禁用环境代理，模型服务是内部调用不走代理
        with httpx.Client(trust_env=False, timeout=rag_config.timeout_seconds) as client:
            resp = client.post(url, json={
                "query":     query,
                "documents": docs_payload,
                "top_k":     min(effective_top_k, len(candidates)),
            })
        resp.raise_for_status()
        data = resp.json()

        # 把 reranker 返回的 id 映射回原始 candidate
        id_to_candidate = {c.get("chunk_id", str(i)): c for i, c in enumerate(candidates)}
        results = []
        for item in data.get("results", []):
            cid = item["id"]
            score = item["score"]
            if score < RELEVANCE_THRESHOLD:
                continue
            original = id_to_candidate.get(cid, {})
            merged = {**original, "rerank_score": score}
            results.append(merged)
        return results

    except Exception as e:
        log.warning("reranker_service_error", extra={"error": str(e), "url": url})
        # Fallback：按向量相似度排序
        sorted_cands = sorted(candidates, key=lambda x: x.get("score", 0), reverse=True)
        top = sorted_cands[:effective_top_k]
        return [
            {**c, "rerank_score": c.get("score", 0.0)}
            for c in top
            if c.get("score", 0) >= RELEVANCE_THRESHOLD
        ]
