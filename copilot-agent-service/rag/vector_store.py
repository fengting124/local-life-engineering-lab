"""
Milvus 向量库封装，兼容 Milvus Lite 与 Milvus Standalone。

连接方式：
  - 本地文件 URI，例如 ``./data/local_life_kb.db``：启动 Milvus Lite
  - HTTP URI，例如 ``http://milvus:19530``：连接 Milvus Standalone

Collection Schema（local_life_kb）：
  chunk_id      VARCHAR(64)        — 分块主键
  doc_id        VARCHAR(64)        — 文档 ID
  content       VARCHAR(2048)      — 原始文本
  embedding     FLOAT_VECTOR(dim)  — 向量
  scope         VARCHAR(20)        — public / merchant_private
  merchant_id   INT64              — 商家 ID
  source        VARCHAR(50)        — 来源类型
  title         VARCHAR(200)       — 文档标题
"""
import datetime
import json
import os
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import urlparse

import structlog

from rag.config import rag_config

log = structlog.get_logger(__name__)

INDEX_TYPE = "IVF_FLAT"
METRIC_TYPE = "IP"


class DimensionMismatchError(Exception):
    """向量维度与 Milvus collection 不匹配。"""


def _is_local_file_uri(uri: str) -> bool:
    """判断 URI 是否为 Milvus Lite 本地数据库文件。"""
    parsed = urlparse(uri)
    return not parsed.scheme or parsed.scheme == "file"


def _prepare_connection_environment(uri: str) -> str:
    """准备本地目录或远端 no_proxy，并返回传给 MilvusClient 的 URI。"""
    if _is_local_file_uri(uri):
        raw_path = uri.removeprefix("file://")
        db_path = Path(raw_path).expanduser()
        db_path.parent.mkdir(parents=True, exist_ok=True)
        return str(db_path)

    host = urlparse(uri).hostname
    if host:
        for key in ("no_proxy", "NO_PROXY"):
            existing = os.environ.get(key, "")
            entries = {item.strip() for item in existing.split(",") if item.strip()}
            entries.update({host, "localhost", "127.0.0.1"})
            os.environ[key] = ",".join(sorted(entries))
    return uri


@contextmanager
def _without_pymilvus_env_uri_for_local_file(uri: str):
    """Avoid pymilvus import-time parsing of local MILVUS_URI values.

    pymilvus 2.4.x reads the MILVUS_URI environment variable while importing
    the package and only accepts remote HTTP(S) endpoints there. The explicit
    MilvusClient(uri=...) argument supports Lite database files, so keep the
    project config unchanged but hide the env var during client creation.
    """
    if not _is_local_file_uri(uri):
        yield
        return

    original = os.environ.pop("MILVUS_URI", None)
    try:
        yield
    finally:
        if original is not None:
            os.environ["MILVUS_URI"] = original


def _escape_filter_string(value: str) -> str:
    """转义 Milvus filter 中的字符串字面量。"""
    return value.replace("\\", "\\\\").replace("'", "\\'")


class MilvusVectorStore:
    """Milvus 实现，满足 ``rag.vector_store_base.VectorStore`` 协议。"""

    def __init__(self, uri: str, collection_name: str):
        self.uri = _prepare_connection_environment(uri)
        self.collection_name = collection_name
        self._client = None
        self._actual_dim: int | None = None

    def _get_client(self):
        if self._client is not None:
            return self._client
        if not self.uri:
            return None
        try:
            with _without_pymilvus_env_uri_for_local_file(self.uri):
                from pymilvus import MilvusClient

                self._client = MilvusClient(uri=self.uri)
            log.info(
                "milvus_connected",
                uri=self.uri,
                mode="lite" if _is_local_file_uri(self.uri) else "standalone",
            )
            self._ensure_collection()
            return self._client
        except Exception as exc:
            log.warning("milvus_unavailable", uri=self.uri, error=str(exc))
            return None

    def _ensure_collection(self) -> None:
        """创建 collection，或验证已有 collection 的向量维度。"""
        from pymilvus import DataType

        client = self._client
        configured_dim = rag_config.embedding_dimension
        configured_model = rag_config.embedding_model_name

        if client.has_collection(self.collection_name):
            actual_dim = self._get_collection_dim()
            if actual_dim is not None and actual_dim != configured_dim:
                raise DimensionMismatchError(
                    f"Milvus collection '{self.collection_name}' 的向量维度为 {actual_dim}，"
                    f"当前 EMBEDDING_DIMENSION={configured_dim}（模型：{configured_model}）。\n"
                    "请新建 collection，或恢复原 embedding 模型后重新启动。"
                )
            self._actual_dim = actual_dim or configured_dim
            log.info(
                "milvus_collection_verified",
                collection=self.collection_name,
                dim=self._actual_dim,
            )
            return

        schema = client.create_schema(
            auto_id=False,
            enable_dynamic_field=False,
            description=json.dumps(
                {
                    "embedding_model": configured_model,
                    "embedding_dimension": configured_dim,
                "created_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                }
            ),
        )
        schema.add_field("chunk_id", DataType.VARCHAR, max_length=64, is_primary=True)
        schema.add_field("doc_id", DataType.VARCHAR, max_length=64)
        schema.add_field("content", DataType.VARCHAR, max_length=2048)
        schema.add_field("embedding", DataType.FLOAT_VECTOR, dim=configured_dim)
        schema.add_field("scope", DataType.VARCHAR, max_length=20)
        schema.add_field("merchant_id", DataType.INT64)
        schema.add_field("source", DataType.VARCHAR, max_length=50)
        schema.add_field("title", DataType.VARCHAR, max_length=200)

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
        self._actual_dim = configured_dim
        log.info(
            "milvus_collection_created",
            collection=self.collection_name,
            dim=configured_dim,
            model=configured_model,
        )

    def _get_collection_dim(self) -> int | None:
        try:
            schema = self._client.describe_collection(self.collection_name)
            for field in schema.get("fields", []):
                if field.get("name") == "embedding":
                    return field.get("params", {}).get("dim")
        except Exception as exc:
            log.warning("milvus_get_dim_failed", error=str(exc))
        return None

    def reset(self) -> bool:
        """删除并重建 collection。"""
        client = self._get_client()
        if client is None:
            log.warning(
                "milvus_reset_skipped",
                reason="Milvus unavailable",
                collection=self.collection_name,
            )
            return False
        try:
            if client.has_collection(self.collection_name):
                client.drop_collection(self.collection_name)
                log.info("milvus_collection_dropped", collection=self.collection_name)
            self._actual_dim = None
            self._ensure_collection()
            return True
        except Exception as exc:
            log.error("milvus_reset_failed", collection=self.collection_name, error=str(exc))
            return False

    def reset_collection(self) -> bool:
        """兼容旧调用路径。"""
        return self.reset()

    def upsert(self, documents: list[dict]) -> int:
        """批量插入或更新文档向量。"""
        client = self._get_client()
        if client is None:
            log.warning("milvus_upsert_skipped", reason="Milvus unavailable", count=len(documents))
            return 0

        if documents:
            actual_vec_dim = len(documents[0].get("embedding", []))
            configured_dim = rag_config.embedding_dimension
            if actual_vec_dim != configured_dim:
                raise DimensionMismatchError(
                    f"写入向量维度 {actual_vec_dim} 与 EMBEDDING_DIMENSION={configured_dim} 不一致。"
                )

        try:
            client.upsert(collection_name=self.collection_name, data=documents)
            log.info("milvus_upserted", count=len(documents))
            return len(documents)
        except DimensionMismatchError:
            raise
        except Exception as exc:
            log.error("milvus_upsert_failed", error=str(exc))
            return 0

    def delete_by_doc_id(self, doc_id: str) -> int:
        """删除源文档的全部 chunk，支持后续离线索引更新和撤回。"""
        if not doc_id:
            return 0
        client = self._get_client()
        if client is None:
            return 0
        try:
            result = client.delete(
                collection_name=self.collection_name,
                filter=f"doc_id == '{_escape_filter_string(doc_id)}'",
            )
            deleted = (
                len(result)
                if isinstance(result, list)
                else int((result or {}).get("delete_count", 0))
            )
            log.info("milvus_document_deleted", doc_id=doc_id, deleted=deleted)
            return deleted
        except Exception as exc:
            log.error("milvus_delete_failed", doc_id=doc_id, error=str(exc))
            return 0

    def search(
        self,
        query_vector: list[float],
        merchant_id: int | None,
        top_k: int | None = None,
    ) -> list[dict]:
        """执行 metadata 权限过滤后的向量搜索。"""
        effective_top_k = top_k if top_k is not None else rag_config.top_k_recall
        client = self._get_client()
        if client is None:
            log.warning(
                "milvus_search_unavailable",
                reason="Milvus client unavailable",
                collection=self.collection_name,
            )
            return []

        if merchant_id is not None:
            filter_expr = (
                "scope == 'public' or "
                f"(scope == 'merchant_private' and merchant_id == {int(merchant_id)})"
            )
        else:
            filter_expr = "scope == 'public'"

        try:
            results = client.search(
                collection_name=self.collection_name,
                data=[query_vector],
                limit=effective_top_k,
                filter=filter_expr,
                output_fields=[
                    "chunk_id",
                    "doc_id",
                    "content",
                    "scope",
                    "merchant_id",
                    "source",
                    "title",
                ],
                search_params={"metric_type": METRIC_TYPE, "params": {"nprobe": 16}},
            )
            return [
                {
                    "chunk_id": hit["id"],
                    "doc_id": hit["entity"].get("doc_id", ""),
                    "content": hit["entity"].get("content", ""),
                    "title": hit["entity"].get("title", ""),
                    "source": hit["entity"].get("source", ""),
                    "score": hit["distance"],
                }
                for hit in results[0]
            ]
        except Exception as exc:
            log.error("milvus_search_failed", error=str(exc))
            return []
