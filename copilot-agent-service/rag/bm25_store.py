"""
BM25 关键词检索存储（内存索引，与 Milvus 向量搜索互补）。

为什么需要 BM25（来自面试高频题 Top50 Q10 的补充）：
  向量搜索（Bi-Encoder）擅长语义相似度，但对「专有名词、精确短词、订单号」等
  关键词检索效果差（这些词的 embedding 相似度计算不稳定）。
  BM25 基于词频统计，对精确关键词匹配效果极好。

  典型失败案例（纯向量）：
    查询「双十一活动 5 折」→ 向量找到「双十二活动 8 折」（语义相似但内容不同）
    查询「ORDER_12345」→ 向量无能为力（特殊字符无语义）

  混合检索（BM25 + 向量 + RRF 融合）同时覆盖语义和关键词，两者互补。

实现方式：
  使用 rank-bm25 库（pip install rank-bm25），内存 BM25 索引。
  知识库文档数量有限（通常 <1000 篇），内存占用可控（<10MB）。

局限性（诚实承认）：
  1. 内存索引：服务重启后需要重建（调用 build_index 方法）
  2. 中文分词：rank-bm25 按空格分词，中文需要先分词（简化版：字符级分词）
  3. BM25 对语义弱：「GMV」和「销售额」是同义词，BM25 不知道
  以上三点在面试时要能诚实说出来，并给出下一步改进方向

权限过滤：
  与 Milvus 一致，scope=public 所有人可见，scope=merchant_private 只对对应商家可见。
  BM25 侧通过 metadata 字段过滤，不在 BM25 分数中处理。
"""
import re
import structlog
from typing import NamedTuple

log = structlog.get_logger(__name__)

# =========================================================
# 文档存储结构
# =========================================================


class BM25Doc(NamedTuple):
    """BM25 索引中的文档条目。"""
    chunk_id: str
    doc_id: str
    content: str
    title: str
    source: str
    scope: str          # public / merchant_private
    merchant_id: int    # 0 表示 public


# =========================================================
# BM25 存储（内存单例）
# =========================================================

class BM25Store:
    """
    内存 BM25 检索存储。

    与 Milvus 同步更新：
    - ingest_document() 调用时，pipeline.py 同时更新 Milvus 和 BM25 索引
    - 服务重启时，需要重新 ingest 所有文档（pipeline.ingest_document）

    线程安全：Python GIL 保护单线程写操作，asyncio 单线程模型下安全。
    """

    def __init__(self):
        self._docs: list[BM25Doc] = []
        self._bm25 = None        # rank_bm25.BM25Okapi 实例
        self._tokenized: list[list[str]] = []

    def add_document(self, doc: BM25Doc) -> None:
        """添加文档到 BM25 索引（同时更新内部 BM25 模型）。"""
        self._docs.append(doc)
        tokens = self._tokenize(doc.content)
        self._tokenized.append(tokens)
        # 每次 add 都重建索引（文档数量少时可接受；大量批量时应用 build_index）
        self._rebuild()
        log.debug("bm25_doc_added", chunk_id=doc.chunk_id, tokens=len(tokens))

    def build_index(self, docs: list[BM25Doc]) -> None:
        """批量构建索引（比逐个 add 更高效）。"""
        self._docs = docs
        self._tokenized = [self._tokenize(d.content) for d in docs]
        self._rebuild()
        log.info("bm25_index_built", doc_count=len(docs))

    def search(
        self,
        query: str,
        merchant_id: int | None,
        top_k: int = 20,
    ) -> list[dict]:
        """
        BM25 关键词检索，带权限过滤。

        :param query:       查询文本
        :param merchant_id: 商家 ID（None 时只搜 public 文档）
        :param top_k:       返回 top K 结果
        :return: 结果列表，格式与 MilvusVectorStore.search 返回一致（方便 RRF 融合）
        """
        if self._bm25 is None or not self._docs:
            return []

        tokens = self._tokenize(query)
        if not tokens:
            return []

        scores = self._bm25.get_scores(tokens)

        # 构建结果（含权限过滤）
        scored_docs = []
        for i, (doc, score) in enumerate(zip(self._docs, scores)):
            if score <= 0:
                continue
            # 权限过滤（与 Milvus 策略一致）
            if doc.scope == "public":
                pass  # 所有人可见
            elif doc.scope == "merchant_private":
                if merchant_id is None or doc.merchant_id != merchant_id:
                    continue  # 私有文档，无权访问
            else:
                continue

            scored_docs.append({
                "chunk_id": doc.chunk_id,
                "doc_id":   doc.doc_id,
                "content":  doc.content,
                "title":    doc.title,
                "source":   doc.source,
                "score":    float(score),   # BM25 原始分数（与向量相似度量纲不同，RRF 按排名融合）
            })

        # 按分数降序，取 top_k
        scored_docs.sort(key=lambda x: x["score"], reverse=True)
        return scored_docs[:top_k]

    def clear(self) -> None:
        """清空索引（重建时使用）。"""
        self._docs.clear()
        self._tokenized.clear()
        self._bm25 = None

    @property
    def doc_count(self) -> int:
        return len(self._docs)

    # =========================================================
    # 私有方法
    # =========================================================

    def _tokenize(self, text: str) -> list[str]:
        """
        简单中英文分词：
        - 英文按空格和标点分词
        - 中文按字符分词（每个汉字作为一个 token）
        - 过滤掉长度 < 1 的 token 和纯数字短词

        局限性：没有使用 jieba 等专业分词工具，「销售额」会被切成「销」「售」「额」，
        与查询「销售额」的词组匹配效果较差。
        改进方向：接入 jieba 分词 + 停用词过滤。
        """
        # 先小写，统一处理
        text = text.lower()
        # 提取中文字符串（整词），和英文词、数字
        chinese_chars = re.findall(r'[一-鿿]', text)
        english_words = re.findall(r'[a-z0-9_\-]{2,}', text)
        return chinese_chars + english_words

    def _rebuild(self) -> None:
        """重建 BM25 模型（内部调用）。"""
        if not self._tokenized:
            self._bm25 = None
            return
        try:
            from rank_bm25 import BM25Okapi
            self._bm25 = BM25Okapi(self._tokenized)
        except ImportError:
            log.warning("rank_bm25_not_installed",
                        hint="pip install rank-bm25==0.2.2")
            self._bm25 = None


# =========================================================
# 全局单例
# =========================================================

# 进程级 BM25 索引，与 Milvus 同步更新
bm25_store = BM25Store()
