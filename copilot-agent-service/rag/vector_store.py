"""
Milvus 向量库封装。

功能：
  1. 建表（如不存在）
  2. 插入文档（upsert）
  3. 向量搜索 Top K（带 metadata 过滤）
  4. 权限过滤：scope=public OR (scope=merchant_private AND merchant_id=X)

Milvus Collection Schema（locallife_knowledge）：
  doc_id      VARCHAR(64)   — 文档唯一 ID（主键）
  chunk_id    VARCHAR(64)   — 分块 ID（一个文档多个分块）
  content     VARCHAR(2048) — 原始文本内容
  embedding   FLOAT_VECTOR(768) — 向量
  scope       VARCHAR(20)   — 权限范围：public / merchant_private
  merchant_id INT64         — 商家 ID（scope=public 时为 0）
  source      VARCHAR(50)   — 来源：platform_rule / merchant_manual / faq / announcement
  title       VARCHAR(200)  — 文档标题

开发模式（Milvus 不可用时）：
  使用内存 Mock 向量库，仅做关键词匹配（不做真实向量搜索）。
  设置 MILVUS_URI="" 触发 Mock 模式。
"""
import structlog
from typing import Any

log = structlog.get_logger(__name__)

# Milvus Collection 名称与 Schema 常量
COLLECTION_NAME     = "locallife_knowledge"
EMBEDDING_DIM       = 768
INDEX_TYPE          = "IVF_FLAT"      # 生产推荐 HNSW（速度快），IVF_FLAT 更简单
METRIC_TYPE         = "IP"            # Inner Product（与 normalize 配合 = cosine similarity）
TOP_K_FETCH         = 20              # 先拉 20 条，再用 reranker 裁剪到 Top 5


class MilvusVectorStore:
    """
    Milvus 向量库封装（异步友好的同步接口）。

    Milvus Python SDK 目前是同步的，通过线程池转异步。
    真正异步版本参考：pymilvus >= 2.5 的 AsyncMilvusClient（experimental）。
    """

    def __init__(self, uri: str, collection_name: str = COLLECTION_NAME):
        self.uri             = uri
        self.collection_name = collection_name
        self._client         = None
        self._available      = False

    def _get_client(self):
        """懒加载 Milvus 连接（连接失败时降级为 Mock）。"""
        if self._client is not None:
            return self._client
        if not self.uri:
            return None
        try:
            from pymilvus import MilvusClient
            self._client    = MilvusClient(uri=self.uri)
            self._available = True
            log.info("milvus_connected", uri=self.uri)
            self._ensure_collection()
            return self._client
        except Exception as e:
            log.warning("milvus_unavailable", error=str(e), hint="Milvus 未启动，使用 Mock 模式")
            return None

    def _ensure_collection(self):
        """确保 Collection 存在，不存在则创建（含索引）。"""
        from pymilvus import MilvusClient, DataType
        client = self._client
        if client.has_collection(self.collection_name):
            return

        schema = client.create_schema(auto_id=False, enable_dynamic_field=False)
        schema.add_field("chunk_id",    DataType.VARCHAR, max_length=64,   is_primary=True)
        schema.add_field("doc_id",      DataType.VARCHAR, max_length=64)
        schema.add_field("content",     DataType.VARCHAR, max_length=2048)
        schema.add_field("embedding",   DataType.FLOAT_VECTOR, dim=EMBEDDING_DIM)
        schema.add_field("scope",       DataType.VARCHAR, max_length=20)
        schema.add_field("merchant_id", DataType.INT64)
        schema.add_field("source",      DataType.VARCHAR, max_length=50)
        schema.add_field("title",       DataType.VARCHAR, max_length=200)

        index_params = client.prepare_index_params()
        index_params.add_index(
            field_name="embedding",
            index_type=INDEX_TYPE,
            metric_type=METRIC_TYPE,
            params={"nlist": 128},
        )

        client.create_collection(
            collection_name=self.collection_name,
            schema=schema,
            index_params=index_params,
        )
        log.info("milvus_collection_created", collection=self.collection_name)

    # =========================================================
    # 写入：文档向量化入库
    # =========================================================

    def upsert(self, documents: list[dict]) -> int:
        """
        批量插入/更新文档向量。

        :param documents: 每个 dict 包含：
          chunk_id, doc_id, content, embedding, scope, merchant_id, source, title
        :return: 成功写入条数
        """
        client = self._get_client()
        if client is None:
            log.warning("milvus_upsert_skipped", reason="Milvus 不可用", count=len(documents))
            return 0

        try:
            client.upsert(collection_name=self.collection_name, data=documents)
            log.info("milvus_upserted", count=len(documents))
            return len(documents)
        except Exception as e:
            log.error("milvus_upsert_failed", error=str(e))
            return 0

    # =========================================================
    # 读取：权限感知向量搜索
    # =========================================================

    def search(
        self,
        query_vector: list[float],
        merchant_id: int | None,
        top_k: int = TOP_K_FETCH,
    ) -> list[dict]:
        """
        向量搜索，带权限过滤。

        权限规则（来自设计文档 7.3）：
          scope=public                         → 所有人可见
          scope=merchant_private AND merchant_id=X → 只有商家 X 可见
          cs/admin 角色：merchant_id=None 时只搜 public 文档

        Milvus filter 表达式：
          "scope == 'public' or (scope == 'merchant_private' and merchant_id == 20001)"

        :param query_vector:  查询向量（已归一化）
        :param merchant_id:   当前商家 ID（merchant 角色必填，cs/admin 可为 None）
        :param top_k:         返回 top K 结果（默认 20，再由 reranker 裁剪）
        :return: 搜索结果列表
        """
        client = self._get_client()
        if client is None:
            return self._mock_search()

        # 构建权限过滤表达式
        if merchant_id is not None:
            filter_expr = (
                f"scope == 'public' or "
                f"(scope == 'merchant_private' and merchant_id == {merchant_id})"
            )
        else:
            # cs/admin 或 merchant_id 为空：只搜公共文档
            filter_expr = "scope == 'public'"

        try:
            results = client.search(
                collection_name=self.collection_name,
                data=[query_vector],
                limit=top_k,
                filter=filter_expr,
                output_fields=["chunk_id", "doc_id", "content", "scope", "merchant_id", "source", "title"],
                search_params={"metric_type": METRIC_TYPE, "params": {"nprobe": 16}},
            )
            return [
                {
                    "chunk_id":    hit["id"],
                    "doc_id":      hit["entity"].get("doc_id", ""),
                    "content":     hit["entity"].get("content", ""),
                    "title":       hit["entity"].get("title", ""),
                    "source":      hit["entity"].get("source", ""),
                    "score":       hit["distance"],
                }
                for hit in results[0]
            ]
        except Exception as e:
            log.error("milvus_search_failed", error=str(e))
            return []

    def _mock_search(self) -> list[dict]:
        """Milvus 不可用时的 Mock 结果（开发调试用）。"""
        return [
            {
                "chunk_id": "mock-001",
                "doc_id":   "mock-doc-001",
                "content":  "[Mock] Milvus 未启动，返回 Mock 搜索结果。请检查 MILVUS_URI 配置。",
                "title":    "Mock 文档",
                "source":   "mock",
                "score":    0.5,
            }
        ]
