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


def _rrf_merge(
    result_lists: list[list[dict]],
    k: int = 60,
) -> list[dict]:
    """
    Reciprocal Rank Fusion（RRF）：多路召回结果融合。

    RRF 公式：score(d) = Σ 1/(k + rank_i(d))
    其中 rank_i(d) 是文档 d 在第 i 路结果中的排名（从 1 开始）。

    RRF 特点：
    - 不依赖原始分数的量纲（向量相似度和 BM25 分数无法直接相加）
    - 对高排名文档给予更高权重（排名 1 得 1/61，排名 20 得 1/80）
    - k=60 是经验值，来自原始 RRF 论文（Cormack et al. 2009）

    :param result_lists: 多路召回结果（每路是 list[dict]，含 chunk_id 字段）
    :param k: RRF 常数（防止排名 1 得分过高）
    :return: 按 RRF 分数降序排列的融合结果（去重，每个 chunk_id 只出现一次）
    """
    rrf_scores: dict[str, float] = {}
    doc_by_id: dict[str, dict] = {}

    for result_list in result_lists:
        for rank, doc in enumerate(result_list, start=1):
            cid = doc.get("chunk_id", "")
            if not cid:
                continue
            rrf_scores[cid] = rrf_scores.get(cid, 0.0) + 1.0 / (k + rank)
            doc_by_id[cid] = doc  # 保留文档元数据（后面 chunk_id 出现多次取任意一个都行）

    # 按 RRF 分数降序排列
    sorted_ids = sorted(rrf_scores, key=lambda x: rrf_scores[x], reverse=True)
    result = []
    for cid in sorted_ids:
        doc = dict(doc_by_id[cid])
        doc["rrf_score"] = rrf_scores[cid]
        result.append(doc)
    return result


async def retrieve(
    query: str,
    merchant_id: int | None,
    top_k: int = 5,
) -> RagResult:
    """
    Hybrid RAG 检索流水线（向量 + BM25 多路召回 + RRF 融合 + Cross-Encoder 精排）。

    检索链路（来自面试高频题）：
    1. Query Rewrite：简单预处理（生产可升级为 LLM 改写）
    2. 并行多路召回：
       - Milvus 向量搜索 Top 20（语义相似度，擅长语义查询）
       - BM25 关键词搜索 Top 20（词频统计，擅长精确词查询）
    3. RRF 融合：按排名加权合并两路结果（不依赖分数量纲）
    4. Cross-Encoder Reranker：对融合后的 Top 20 精排，过滤低相关文档
    5. 阈值拒答：rerank_score < 0.3 时拒绝回答（防止编造内容）

    面试追问「为什么需要 BM25，向量搜索不够吗？」：
    → 向量对「ORDER_12345」「双十一 5 折券」等专有词/精确词召回不稳定；
    BM25 正好弥补这个弱点；两者结合 + RRF 融合覆盖语义和关键词两个维度。

    :param query:       用户查询文本（原始，未改写）
    :param merchant_id: 商家 ID（merchant 角色必填，cs/admin 可 None）
    :param top_k:       最终传给 LLM 的文档数（默认 5）
    :return: RagResult（含 context_text 和是否拒答标志）
    """
    log.info("rag_retrieve_start", query=query[:50], merchant_id=merchant_id)

    # ---- Step 1: Query Rewrite ----
    rewritten_query = _simple_rewrite(query)

    # ---- Step 2: 并行双路召回 ----
    loop = asyncio.get_event_loop()

    # 路径 A：Milvus 向量搜索（语义）
    query_vector = await loop.run_in_executor(None, embed_query, rewritten_query)
    vs = _get_vector_store()
    vector_docs = await loop.run_in_executor(None, vs.search, query_vector, merchant_id, 20)

    # 路径 B：BM25 关键词搜索（精确词）
    from rag.bm25_store import bm25_store
    bm25_docs = await loop.run_in_executor(
        None, bm25_store.search, rewritten_query, merchant_id, 20
    )

    log.info("rag_dual_recall",
             vector_count=len(vector_docs),
             bm25_count=len(bm25_docs))

    # ---- Step 3: RRF 融合（如果 BM25 索引为空，只用向量结果）----
    if bm25_docs:
        merged = _rrf_merge([vector_docs, bm25_docs], k=60)
    else:
        merged = vector_docs  # BM25 索引未建立时降级为纯向量

    if not merged:
        log.info("rag_no_candidates", query=query[:50])
        return RagResult(docs=[], refused=True)

    # ---- Step 4: Reranker 精排（取前 20 个输入给 Reranker）----
    reranked = await loop.run_in_executor(None, rerank, rewritten_query, merged[:20])

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

    # ---- Milvus 向量入库 ----
    vs = _get_vector_store()
    n  = await loop.run_in_executor(None, vs.upsert, rows)

    # ---- BM25 索引同步更新 ----
    # 与 Milvus 同步维护 BM25 内存索引，确保混合检索的 BM25 路径有数据
    # 注意：服务重启后 BM25 索引丢失，需要重新 ingest 所有文档
    from rag.bm25_store import bm25_store, BM25Doc
    for row in rows:
        bm25_store.add_document(BM25Doc(
            chunk_id=row["chunk_id"],
            doc_id=row["doc_id"],
            content=row["content"],
            title=row["title"],
            source=row["source"],
            scope=row["scope"],
            merchant_id=row["merchant_id"],
        ))

    log.info("document_ingested", doc_id=doc_id, chunks=n,
             bm25_total=bm25_store.doc_count)
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
