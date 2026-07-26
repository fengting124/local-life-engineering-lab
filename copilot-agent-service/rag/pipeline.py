"""RAG 权限感知检索流水线。

在线链路：
  Query Normalize
  -> Dense Vector Search
  -> BM25 Search
  -> RRF Fusion
  -> Cross-Encoder Rerank
  -> Threshold Refusal
  -> Context with Sources

Pipeline 本身无状态。向量后端通过工厂创建，本地文件 URI 使用 Milvus Lite，远端
HTTP URI 使用 Milvus Standalone。
"""
import asyncio
from functools import lru_cache

import structlog

from agent.trace import genai_span
from rag.embedding_client import embed_query
from rag.reranker_client import rerank
from rag.vector_store_base import VectorStore
from rag.vector_store_factory import create_vector_store

log = structlog.get_logger(__name__)

MAX_CHUNK_CHARS = 800


@lru_cache(maxsize=1)
def _get_vector_store() -> VectorStore:
    """获取全局向量存储单例，具体实现由配置工厂决定。"""
    return create_vector_store()


class RagResult:
    """RAG 检索结果。"""

    def __init__(self, docs: list[dict], refused: bool = False):
        self.docs = docs
        self.refused = refused
        self.context_text = self._build_context()

    def _build_context(self) -> str:
        if self.refused:
            return ""
        parts = []
        for index, doc in enumerate(self.docs, 1):
            content = doc["content"][:MAX_CHUNK_CHARS]
            title = doc.get("title", "未知文档")
            source = doc.get("source", "")
            parts.append(f"[{index}] 《{title}》（来源：{source}）\n{content}")
        return "\n\n---\n\n".join(parts)

    def to_dict(self) -> dict:
        return {
            "refused": self.refused,
            "doc_count": len(self.docs),
            "context_text": self.context_text,
            "sources": [
                {"title": doc.get("title"), "source": doc.get("source")}
                for doc in self.docs
            ],
        }


def _rrf_merge(
    result_lists: list[list[dict]],
    k: int = 60,
) -> list[dict]:
    """使用 Reciprocal Rank Fusion 合并多路召回结果。"""
    rrf_scores: dict[str, float] = {}
    doc_by_id: dict[str, dict] = {}

    for result_list in result_lists:
        for rank, doc in enumerate(result_list, start=1):
            chunk_id = doc.get("chunk_id", "")
            if not chunk_id:
                continue
            rrf_scores[chunk_id] = rrf_scores.get(chunk_id, 0.0) + 1.0 / (k + rank)
            doc_by_id[chunk_id] = doc

    sorted_ids = sorted(rrf_scores, key=lambda item: rrf_scores[item], reverse=True)
    result = []
    for chunk_id in sorted_ids:
        doc = dict(doc_by_id[chunk_id])
        doc["rrf_score"] = rrf_scores[chunk_id]
        result.append(doc)
    return result


async def retrieve(
    query: str,
    merchant_id: int | None,
    top_k: int = 5,
) -> RagResult:
    """执行 Hybrid RAG 检索。

    Dense Search 负责语义召回，BM25 负责订单号、活动名等精确词召回。两路结果使用
    RRF 合并后进入 Reranker。无候选或精排后无结果时 fail closed。
    """
    log.info("rag_retrieve_start", query=query[:50], merchant_id=merchant_id)

    normalized_query = normalize_query(query)
    loop = asyncio.get_running_loop()
    merchant_scoped = merchant_id is not None

    async with genai_span(
        "rag.total",
        "rag",
        top_k=top_k,
        merchant_scoped=merchant_scoped,
        query_len=len(normalized_query),
    ):
        async with genai_span(
            "rag.embedding",
            "embedding",
            merchant_scoped=merchant_scoped,
            query_len=len(normalized_query),
        ):
            query_vector = await loop.run_in_executor(None, embed_query, normalized_query)

        vector_store = _get_vector_store()
        async with genai_span(
            "rag.vector_search",
            "vector_store",
            merchant_scoped=merchant_scoped,
            top_k=20,
        ):
            vector_docs = await loop.run_in_executor(
                None,
                vector_store.search,
                query_vector,
                merchant_id,
                20,
            )

        from rag.bm25_store import bm25_store

        async with genai_span(
            "rag.bm25_search",
            "keyword_store",
            merchant_scoped=merchant_scoped,
            top_k=20,
        ):
            bm25_docs = await loop.run_in_executor(
                None,
                bm25_store.search,
                normalized_query,
                merchant_id,
                20,
            )

        log.info(
            "rag_dual_recall",
            vector_count=len(vector_docs),
            bm25_count=len(bm25_docs),
        )

        merged = _rrf_merge([vector_docs, bm25_docs], k=60) if bm25_docs else vector_docs
        if not merged:
            log.info("rag_no_candidates", query=query[:50])
            return RagResult(docs=[], refused=True)

        async with genai_span(
            "rag.rerank",
            "reranker",
            candidate_count=len(merged[:20]),
            merchant_scoped=merchant_scoped,
            top_k=top_k,
        ):
            reranked = await loop.run_in_executor(None, rerank, normalized_query, merged[:20])
        if not reranked:
            log.info("rag_below_threshold", query=query[:50])
            return RagResult(docs=[], refused=True)

        log.info(
            "rag_retrieve_done",
            query=query[:50],
            vector_count=len(vector_docs),
            bm25_count=len(bm25_docs),
            merged_count=len(merged),
            final_count=len(reranked),
            top_score=reranked[0].get("rerank_score", 0),
        )
        return RagResult(docs=reranked[:top_k])


def normalize_query(query: str) -> str:
    """做确定性的查询规范化，不宣称已完成语义 Query Rewrite。"""
    return " ".join(query.strip().split())


def _simple_rewrite(query: str) -> str:
    """兼容旧测试和调用路径，后续删除。"""
    return normalize_query(query)


async def ingest_document(
    doc_id: str,
    title: str,
    content: str,
    source: str,
    scope: str = "public",
    merchant_id: int = 0,
    chunk_size: int = 500,
    overlap: int = 50,
) -> int:
    """切分文档、批量向量化，并同步更新 Dense 与 BM25 索引。"""
    chunks = _split_text(content, chunk_size, overlap)
    if not chunks:
        return 0

    loop = asyncio.get_running_loop()
    from rag.embedding_client import embed_documents_batch

    vectors = await loop.run_in_executor(None, embed_documents_batch, chunks)
    rows = [
        {
            "chunk_id": f"{doc_id}_{index}",
            "doc_id": doc_id,
            "content": chunk,
            "embedding": vector,
            "scope": scope,
            "merchant_id": merchant_id,
            "source": source,
            "title": title,
        }
        for index, (chunk, vector) in enumerate(zip(chunks, vectors))
    ]

    vector_store = _get_vector_store()
    written = await loop.run_in_executor(None, vector_store.upsert, rows)

    from rag.bm25_store import BM25Doc, bm25_store

    for row in rows:
        bm25_store.add_document(
            BM25Doc(
                chunk_id=row["chunk_id"],
                doc_id=row["doc_id"],
                content=row["content"],
                title=row["title"],
                source=row["source"],
                scope=row["scope"],
                merchant_id=row["merchant_id"],
            )
        )

    log.info(
        "document_ingested",
        doc_id=doc_id,
        chunks=written,
        bm25_total=bm25_store.doc_count,
    )
    return written


def _split_text(text: str, chunk_size: int, overlap: int) -> list[str]:
    """按滑动窗口切分文本。"""
    if not text:
        return []
    if chunk_size <= 0:
        raise ValueError("chunk_size must be positive")
    if overlap < 0 or overlap >= chunk_size:
        raise ValueError("overlap must satisfy 0 <= overlap < chunk_size")

    chunks = []
    start = 0
    while start < len(text):
        end = min(start + chunk_size, len(text))
        chunks.append(text[start:end])
        if end == len(text):
            break
        start += chunk_size - overlap
    return chunks
