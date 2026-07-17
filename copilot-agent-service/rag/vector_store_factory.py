"""向量存储工厂。

当前只开放 Milvus 后端。`MILVUS_URI` 传本地 `.db` 路径时使用 Milvus Lite，传
HTTP Endpoint 时连接 Milvus Standalone。后续增加其他后端时只扩展本工厂和实现类。
"""
from rag.config import RagConfig, rag_config
from rag.vector_store import MilvusVectorStore
from rag.vector_store_base import VectorStore


def create_vector_store(config: RagConfig = rag_config) -> VectorStore:
    """根据配置创建向量存储实例。

    未知后端直接失败，避免配置拼写错误时静默降级为错误的数据源。
    """
    backend = config.vector_backend.strip().lower()
    if backend == "milvus":
        return MilvusVectorStore(
            uri=config.milvus_uri,
            collection_name=config.milvus_collection,
        )
    raise ValueError(f"Unsupported VECTOR_BACKEND: {config.vector_backend}")
