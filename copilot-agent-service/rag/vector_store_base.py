"""向量存储后端的最小稳定接口。

RAG Pipeline 只依赖该协议，不直接依赖 Milvus 的具体实现。这样本地开发可以使用
Milvus Lite 文件，完整部署可以切换到 Milvus Standalone，调用侧无需修改。
"""
from typing import Protocol, runtime_checkable


@runtime_checkable
class VectorStore(Protocol):
    """RAG 所需的向量存储能力。"""

    def upsert(self, documents: list[dict]) -> int:
        """批量写入或更新文档块，返回成功写入数量。"""
        ...

    def search(
        self,
        query_vector: list[float],
        merchant_id: int | None,
        top_k: int | None = None,
    ) -> list[dict]:
        """执行权限感知的向量检索。"""
        ...

    def delete_by_doc_id(self, doc_id: str) -> int:
        """删除一个源文档对应的全部分块，返回删除数量。"""
        ...

    def reset(self) -> bool:
        """重建当前向量索引。"""
        ...
