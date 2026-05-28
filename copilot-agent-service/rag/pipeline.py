"""
RAG 完整流水线（权限感知版）。

设计文档 7.2 / 7.3 的完整实现：

  用户查询
    ↓
  Query Rewrite（改写扩充，提升召回率）
    ↓
  Milvus 向量搜索 Top 20（IP 相似度 + metadata 权限过滤）
    ↓
  Reranker 精排 Top 5（Cross-Encoder 提升相关性）
    ↓
  分数阈值过滤（低于 0.3 拒答，避免编造）
    ↓
  上下文压缩（超长文档截断，保留摘要）
    ↓
  传给 LLM 生成含引用来源的回答

Pipeline 是无状态的（每次调用独立），可安全并发。
"""
import asyncio
import structlog
from functools import lru_cache

from rag.embedding import embed_query
from rag.vector_store import MilvusVectorStore
from rag.reranker import rerank, RELEVANCE_THRESHOLD
from config.settings import settings

log = structlog.get_logger(__name__)

# 内容截断长度（防止单个文档占用过多 token）
MAX_CHUNK_CHARS = 800


@lru_cache(maxsize=1)
def _get_vector_store() -> MilvusVectorStore:
    """获取全局向量库单例（懒加载）。"""
    return MilvusVectorStore(uri=settings.milvus_uri, collection_name=settings.milvus_collection)


class RagResult:
    """RAG 检索结果。"""
    def __init__(self, docs: list[dict], refused: bool = False):
        self.docs         = docs            # 相关文档列表（精排后）
        self.refused      = refused         # True = 无相关文档，应拒答
        self.context_text = self._build_context()

    def _build_context(self) -> str:
        """构建传给 LLM 的上下文文本（含来源引用）。"""
        if self.refused:
            return ""
        parts = []
        for i, doc in enumerate(self.docs, 1):
            content = doc["content"][:MAX_CHUNK_CHARS]
            title   = doc.get("title", "未知文档")
            source  = doc.get("source", "")
            parts.append(f"[{i}] 《{title}》（来源：{source}）\n{content}")
        return "\n\n---\n\n".join(parts)

    def to_dict(self) -> dict:
        return {
            "refused":      self.refused,
            "doc_count":    len(self.docs),
            "context_text": self.context_text,
            "sources":      [{"title": d.get("title"), "source": d.get("source")} for d in self.docs],
        }


async def retrieve(
    query: str,
    merchant_id: int | None,
    top_k: int = 5,
) -> RagResult:
    """
    执行完整 RAG 检索流水线。

    :param query:       用户查询文本（原始，未改写）
    :param merchant_id: 商家 ID（merchant 角色必填，cs/admin 可 None → 只搜 public）
    :param top_k:       最终传给 LLM 的文档数（默认 5）
    :return: RagResult（含 context_text 和是否拒答标志）

    性能预估：
      embed_query: ~10ms（本地 sentence-transformers）
      milvus search: ~5ms（本地 Milvus，百万级向量）
      rerank Top 20: ~30ms（本地 cross-encoder）
      total: ~50ms（P99，不含 LLM 调用）
    """
    log.info("rag_retrieve_start", query=query[:50], merchant_id=merchant_id)

    # ---- Step 1: Query Rewrite（Query 扩充，提升召回率）----
    # 简化版：直接使用原始查询（生产可以加 LLM 改写，把「昨天」改为日期等）
    rewritten_query = _simple_rewrite(query)

    # ---- Step 2: Embedding ----
    # 在线程池中运行 CPU 密集型的 embedding 推理，不阻塞事件循环
    loop = asyncio.get_event_loop()
    query_vector = await loop.run_in_executor(None, embed_query, rewritten_query)

    # ---- Step 3: 向量搜索 Top 20（带权限过滤）----
    vs       = _get_vector_store()
    raw_docs = await loop.run_in_executor(None, vs.search, query_vector, merchant_id, 20)

    if not raw_docs:
        log.info("rag_no_candidates", query=query[:50])
        return RagResult(docs=[], refused=True)

    # ---- Step 4: Reranker 精排 Top 5 ----
    reranked = await loop.run_in_executor(None, rerank, rewritten_query, raw_docs)

    if not reranked:
        # 所有文档均低于相关性阈值 → 拒答
        log.info("rag_below_threshold", query=query[:50], threshold=RELEVANCE_THRESHOLD)
        return RagResult(docs=[], refused=True)

    log.info(
        "rag_retrieve_done",
        query=query[:50],
        raw_count=len(raw_docs),
        final_count=len(reranked),
        top_score=reranked[0].get("rerank_score", 0),
    )
    return RagResult(docs=reranked[:top_k])


def _simple_rewrite(query: str) -> str:
    """
    简单的 Query 预处理：去除多余空白，统一标点。
    生产升级：用 LLM 做语义改写（「昨天销售怎么样」→「昨天 GMV 和订单量」）。
    """
    return " ".join(query.strip().split())


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
    """
    将一篇文档切分并向量化入库（知识库构建用）。

    切分策略：按字符数切分（500 字/块，50 字重叠）。
    生产升级：按段落/句子切分（LangChain RecursiveCharacterTextSplitter）。

    :param doc_id:      文档唯一 ID
    :param title:       文档标题（展示给用户的引用来源）
    :param content:     文档全文
    :param source:      来源类型（platform_rule / merchant_manual / faq / announcement）
    :param scope:       权限范围（public / merchant_private）
    :param merchant_id: 商家 ID（scope=merchant_private 时必填）
    :param chunk_size:  每块字符数
    :param overlap:     相邻块重叠字符数（保证上下文连贯性）
    :return: 入库块数
    """
    # 切分文档
    chunks = _split_text(content, chunk_size, overlap)
    if not chunks:
        return 0

    # 批量向量化（passage 前缀）
    loop  = asyncio.get_event_loop()
    from rag.embedding import embed_texts
    prefixed_chunks  = [f"passage: {c}" for c in chunks]
    vectors = await loop.run_in_executor(None, embed_texts, prefixed_chunks)

    # 构建 Milvus 数据行
    rows = [
        {
            "chunk_id":    f"{doc_id}_{i}",
            "doc_id":      doc_id,
            "content":     chunk,
            "embedding":   vec,
            "scope":       scope,
            "merchant_id": merchant_id,
            "source":      source,
            "title":       title,
        }
        for i, (chunk, vec) in enumerate(zip(chunks, vectors))
    ]

    vs = _get_vector_store()
    n  = await loop.run_in_executor(None, vs.upsert, rows)
    log.info("document_ingested", doc_id=doc_id, chunks=n)
    return n


def _split_text(text: str, chunk_size: int, overlap: int) -> list[str]:
    """滑动窗口切分文本。"""
    if not text:
        return []
    chunks = []
    start  = 0
    while start < len(text):
        end = min(start + chunk_size, len(text))
        chunks.append(text[start:end])
        if end == len(text):
            break
        start += chunk_size - overlap
    return chunks
