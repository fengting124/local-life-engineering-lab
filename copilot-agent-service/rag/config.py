"""
RAG 配置模块，从环境变量读取所有配置。
业务代码通过 rag_config 对象访问，不直接读 os.environ。
"""
import os
from dataclasses import dataclass


@dataclass
class RagConfig:
    embedding_service_url: str
    reranker_service_url: str
    milvus_host: str
    milvus_port: int
    milvus_collection: str
    embedding_model_name: str
    embedding_dimension: int
    top_k_recall: int
    top_k_rerank: int
    min_score: float
    timeout_seconds: float


def load_rag_config() -> RagConfig:
    return RagConfig(
        embedding_service_url=os.getenv("EMBEDDING_SERVICE_URL", "http://localhost:8100"),
        reranker_service_url=os.getenv("RERANKER_SERVICE_URL", "http://localhost:8101"),
        milvus_host=os.getenv("MILVUS_HOST", "localhost"),
        milvus_port=int(os.getenv("MILVUS_PORT", "19530")),
        milvus_collection=os.getenv("MILVUS_COLLECTION", "local_life_kb"),
        embedding_model_name=os.getenv("EMBEDDING_MODEL_NAME", "intfloat/multilingual-e5-base"),
        embedding_dimension=int(os.getenv("EMBEDDING_DIMENSION", "768")),
        top_k_recall=int(os.getenv("RAG_TOP_K_RECALL", "20")),
        top_k_rerank=int(os.getenv("RAG_TOP_K_RERANK", "5")),
        min_score=float(os.getenv("RAG_MIN_SCORE", "0.3")),
        timeout_seconds=float(os.getenv("RAG_TIMEOUT_SECONDS", "10")),
    )


rag_config = load_rag_config()
