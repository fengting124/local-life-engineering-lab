"""
Milvus 向量库封装（含维度保护）。

Milvus Collection Schema（local_life_kb）：
  chunk_id      VARCHAR(64)        — 分块主键
  doc_id        VARCHAR(64)        — 文档 ID
  content       VARCHAR(2048)      — 原始文本
  embedding     FLOAT_VECTOR(dim)  — 向量（dim 来自 EMBEDDING_DIMENSION）
  scope         VARCHAR(20)        — public / merchant_private
  merchant_id   INT64              — 商家 ID（public 时为 0）
  source        VARCHAR(50)        — 来源类型
  title         VARCHAR(200)       — 文档标题

维度保护：
  collection 创建时在 description 字段记录 embedding_model 和 embedding_dimension。
  写入时检查当前配置维度与 collection 实际维度是否一致，不一致则拒绝并提示。
"""
import os
import json
import datetime
import structlog
from typing import Any

from rag.config import rag_config

log = structlog.get_logger(__name__)

# Milvus 走 gRPC，会读取 ALL_PROXY/http_proxy 环境变量。
# 把 Milvus 主机加入 no_proxy，避免 WSL 代理拦截内部 gRPC 连接。
_milvus_host = rag_config.milvus_host
for _key in ("no_proxy", "NO_PROXY"):
    _existing = os.environ.get(_key, "")
    _entries = {e.strip() for e in _existing.split(",") if e.strip()}
    _entries.update({_milvus_host, "localhost", "127.0.0.1"})
    os.environ[_key] = ",".join(sorted(_entries))

INDEX_TYPE  = "IVF_FLAT"
METRIC_TYPE = "IP"


class DimensionMismatchError(Exception):
    """向量维度与 Milvus collection 不匹配时抛出。"""
    pass


class MilvusVectorStore:
    def __init__(self, uri: str, collection_name: str):
        self.uri             = uri
        self.collection_name = collection_name
        self._client         = None
        self._available      = False
        self._actual_dim: int | None = None

    def _get_client(self):
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
            log.warning("milvus_unavailable", error=str(e))
            return None

    # ──────────────────────────────────────────
    # 维度保护：核心逻辑
    # ──────────────────────────────────────────

    def _ensure_collection(self):
        """确保 collection 存在，不存在时创建，存在时验证维度一致性。"""
        from pymilvus import DataType
        client   = self._client
        cfg_dim  = rag_config.embedding_dimension
        cfg_model = rag_config.embedding_model_name

        if client.has_collection(self.collection_name):
            # collection 已存在，读取实际维度并验证
            actual_dim = self._get_collection_dim()
            if actual_dim is not None and actual_dim != cfg_dim:
                raise DimensionMismatchError(
                    f"Milvus collection '{self.collection_name}' 的向量维度为 {actual_dim}，"
                    f"但当前配置 EMBEDDING_DIMENSION={cfg_dim}（模型：{cfg_model}）。\n"
                    f"解决方案：\n"
                    f"  1. 新建 collection：修改 MILVUS_COLLECTION 为新名称（如 local_life_kb_{cfg_dim}）\n"
                    f"  2. 或恢复原模型：将 EMBEDDING_MODEL_NAME 改回 {cfg_dim} 维模型\n"
                    f"  3. 如需迁移：先删除旧 collection，再重新 ingest 所有文档"
                )
            self._actual_dim = actual_dim or cfg_dim
            log.info("milvus_collection_verified", collection=self.collection_name, dim=self._actual_dim)
            return

        # 新建 collection
        schema = client.create_schema(
            auto_id=False,
            enable_dynamic_field=False,
            description=json.dumps({
                "embedding_model": cfg_model,
                "embedding_dimension": cfg_dim,
                "created_at": datetime.datetime.utcnow().isoformat(),
            }),
        )
        schema.add_field("chunk_id",    DataType.VARCHAR, max_length=64,   is_primary=True)
        schema.add_field("doc_id",      DataType.VARCHAR, max_length=64)
        schema.add_field("content",     DataType.VARCHAR, max_length=2048)
        schema.add_field("embedding",   DataType.FLOAT_VECTOR, dim=cfg_dim)
        schema.add_field("scope",       DataType.VARCHAR, max_length=20)
        schema.add_field("merchant_id", DataType.INT64)
        schema.add_field("source",      DataType.VARCHAR, max_length=50)
        schema.add_field("title",       DataType.VARCHAR, max_length=200)

        index_params = client.prepare_index_params()
        index_params.add_index(
            field_name  ="embedding",
            index_type  =INDEX_TYPE,
            metric_type =METRIC_TYPE,
            params      ={"nlist": 128},
        )

        client.create_collection(
            collection_name=self.collection_name,
            schema=schema,
            index_params=index_params,
        )
        self._actual_dim = cfg_dim
        log.info("milvus_collection_created",
                 collection=self.collection_name, dim=cfg_dim, model=cfg_model)

    def _get_collection_dim(self) -> int | None:
        """从 collection schema 读取实际向量维度。"""
        try:
            schema = self._client.describe_collection(self.collection_name)
            for field in schema.get("fields", []):
                if field.get("name") == "embedding":
                    params = field.get("params", {})
                    return params.get("dim")
        except Exception as e:
            log.warning("milvus_get_dim_failed", error=str(e))
        return None

    # ──────────────────────────────────────────
    # 写入
    # ──────────────────────────────────────────

    def upsert(self, documents: list[dict]) -> int:
        """批量插入/更新文档向量，写入前验证维度一致性。"""
        client = self._get_client()
        if client is None:
            log.warning("milvus_upsert_skipped", reason="Milvus 不可用", count=len(documents))
            return 0

        # 验证向量维度
        if documents:
            vec = documents[0].get("embedding", [])
            actual_vec_dim = len(vec)
            cfg_dim = rag_config.embedding_dimension
            if actual_vec_dim != cfg_dim:
                raise DimensionMismatchError(
                    f"写入向量维度 {actual_vec_dim} ≠ 配置 EMBEDDING_DIMENSION={cfg_dim}。"
                    f"请检查 embedding-service 是否已切换模型。"
                )

        try:
            client.upsert(collection_name=self.collection_name, data=documents)
            log.info("milvus_upserted", count=len(documents))
            return len(documents)
        except DimensionMismatchError:
            raise
        except Exception as e:
            log.error("milvus_upsert_failed", error=str(e))
            return 0

    # ──────────────────────────────────────────
    # 读取
    # ──────────────────────────────────────────

    def search(
        self,
        query_vector: list[float],
        merchant_id: int | None,
        top_k: int | None = None,
    ) -> list[dict]:
        """权限感知向量搜索。"""
        effective_top_k = top_k if top_k is not None else rag_config.top_k_recall
        client = self._get_client()
        if client is None:
            return self._mock_search()

        if merchant_id is not None:
            filter_expr = (
                f"scope == 'public' or "
                f"(scope == 'merchant_private' and merchant_id == {merchant_id})"
            )
        else:
            filter_expr = "scope == 'public'"

        try:
            results = client.search(
                collection_name=self.collection_name,
                data=[query_vector],
                limit=effective_top_k,
                filter=filter_expr,
                output_fields=["chunk_id", "doc_id", "content", "scope", "merchant_id", "source", "title"],
                search_params={"metric_type": METRIC_TYPE, "params": {"nprobe": 16}},
            )
            return [
                {
                    "chunk_id":   hit["id"],
                    "doc_id":     hit["entity"].get("doc_id", ""),
                    "content":    hit["entity"].get("content", ""),
                    "title":      hit["entity"].get("title", ""),
                    "source":     hit["entity"].get("source", ""),
                    "score":      hit["distance"],
                }
                for hit in results[0]
            ]
        except Exception as e:
            log.error("milvus_search_failed", error=str(e))
            return []

    def _mock_search(self) -> list[dict]:
        return [{
            "chunk_id": "mock-001",
            "doc_id":   "mock-doc-001",
            "content":  "[Mock] Milvus 未启动，返回 Mock 搜索结果。请检查 MILVUS_URI 配置。",
            "title":    "Mock 文档",
            "source":   "mock",
            "score":    0.5,
        }]
