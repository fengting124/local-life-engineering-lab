"""RAG 共享数据结构。"""
from dataclasses import dataclass, field


@dataclass
class RetrievedDoc:
    chunk_id: str
    doc_id: str
    content: str
    title: str
    source: str
    score: float             # 向量相似度（召回阶段）
    rerank_score: float = 0.0   # cross-encoder 分数（精排阶段）


@dataclass
class RagResult:
    docs: list[RetrievedDoc] = field(default_factory=list)
    refused: bool = False    # True = 无相关文档，应拒答
    context_text: str = ""

    def build_context(self, max_chunk_chars: int = 800) -> str:
        if self.refused or not self.docs:
            return ""
        parts = []
        for i, doc in enumerate(self.docs, 1):
            content = doc.content[:max_chunk_chars]
            parts.append(f"[{i}] 《{doc.title}》（来源：{doc.source}）\n{content}")
        return "\n\n---\n\n".join(parts)

    def to_dict(self) -> dict:
        return {
            "refused":      self.refused,
            "doc_count":    len(self.docs),
            "context_text": self.context_text,
            "sources": [
                {"title": d.title, "source": d.source, "score": d.rerank_score}
                for d in self.docs
            ],
        }
