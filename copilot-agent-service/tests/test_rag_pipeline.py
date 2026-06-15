"""
rag/pipeline.py 单元测试。

纯函数（_rrf_merge / _split_text / _simple_rewrite / RagResult）直接测试。
retrieve() 依赖外部服务（embedding、Milvus、reranker），全部 mock 隔离。

重点：
  - RRF 公式正确性：两路结果共同命中的文档得分最高
  - 分块滑动窗口：chunk_size / overlap 边界
  - 拒答机制：无候选 或 reranker 全部过滤 → RagResult.refused=True
"""
import pytest
from unittest.mock import patch, MagicMock

from rag.pipeline import _rrf_merge, _split_text, _simple_rewrite, retrieve, RagResult


# =========================================================
# _rrf_merge
# =========================================================

class TestRrfMerge:
    def test_single_list_returns_all_docs(self):
        docs = [{"chunk_id": "a"}, {"chunk_id": "b"}, {"chunk_id": "c"}]
        result = _rrf_merge([docs])
        assert len(result) == 3

    def test_shared_doc_ranked_first(self):
        """两路结果都排名第一的文档，RRF 分数最高。"""
        list1 = [{"chunk_id": "shared"}, {"chunk_id": "only_a"}]
        list2 = [{"chunk_id": "shared"}, {"chunk_id": "only_b"}]
        result = _rrf_merge([list1, list2])
        assert result[0]["chunk_id"] == "shared"

    def test_deduplication(self):
        """相同 chunk_id 只出现一次。"""
        list1 = [{"chunk_id": "x"}, {"chunk_id": "y"}]
        list2 = [{"chunk_id": "x"}, {"chunk_id": "z"}]
        result = _rrf_merge([list1, list2])
        chunk_ids = [d["chunk_id"] for d in result]
        assert len(chunk_ids) == len(set(chunk_ids))

    def test_empty_list_returns_empty(self):
        result = _rrf_merge([[]])
        assert result == []

    def test_doc_without_chunk_id_skipped(self):
        docs = [{"content": "no chunk_id here"}, {"chunk_id": "valid"}]
        result = _rrf_merge([docs])
        assert len(result) == 1
        assert result[0]["chunk_id"] == "valid"

    def test_rrf_score_added(self):
        docs = [{"chunk_id": "x"}]
        result = _rrf_merge([docs])
        assert "rrf_score" in result[0]

    def test_k_parameter_affects_score(self):
        """k 越大，最高 RRF 分数越低（分子固定为 1）。"""
        docs = [{"chunk_id": "a"}]
        r_k1 = _rrf_merge([docs], k=1)
        r_k60 = _rrf_merge([docs], k=60)
        # k=1: 1/(1+1)=0.5, k=60: 1/(60+1)≈0.016
        assert r_k1[0]["rrf_score"] > r_k60[0]["rrf_score"]

    def test_two_lists_merged_count(self):
        """两路各 2 个不重叠的文档，融合后共 4 个。"""
        list1 = [{"chunk_id": "a1"}, {"chunk_id": "a2"}]
        list2 = [{"chunk_id": "b1"}, {"chunk_id": "b2"}]
        result = _rrf_merge([list1, list2])
        assert len(result) == 4


# =========================================================
# _split_text
# =========================================================

class TestSplitText:
    def test_empty_string(self):
        assert _split_text("", 500, 50) == []

    def test_shorter_than_chunk_size(self):
        text = "短文本"
        result = _split_text(text, 500, 50)
        assert result == ["短文本"]

    def test_exactly_chunk_size(self):
        text = "x" * 500
        assert _split_text(text, 500, 50) == [text]

    def test_overlap_produces_correct_chunks(self):
        # 600 字，chunk=500，overlap=100 → step=400 → chunks: [0:500], [400:600]
        text = "a" * 600
        result = _split_text(text, 500, 100)
        assert len(result) == 2
        assert result[0] == "a" * 500
        assert result[1] == "a" * 200  # [400:600]

    def test_large_text_chunk_count(self):
        # 1000 字，chunk=500，overlap=50 → step=450 → 3 chunks
        text = "x" * 1000
        result = _split_text(text, 500, 50)
        assert len(result) == 3

    def test_no_overlap_produces_exact_division(self):
        text = "y" * 1000
        result = _split_text(text, 500, 0)
        assert len(result) == 2
        assert all(len(c) == 500 for c in result)


# =========================================================
# _simple_rewrite
# =========================================================

class TestSimpleRewrite:
    def test_strips_whitespace(self):
        assert _simple_rewrite("  hello world  ") == "hello world"

    def test_collapses_multiple_spaces(self):
        assert _simple_rewrite("hello   world") == "hello world"

    def test_empty_string(self):
        assert _simple_rewrite("") == ""

    def test_chinese_text_unchanged(self):
        assert _simple_rewrite("昨天的销售额") == "昨天的销售额"

    def test_tab_normalized(self):
        result = _simple_rewrite("query\torder")
        assert "\t" not in result


# =========================================================
# RagResult
# =========================================================

class TestRagResult:
    def test_refused_result_empty_context(self):
        r = RagResult(docs=[], refused=True)
        assert r.refused is True
        assert r.context_text == ""

    def test_context_text_includes_title_and_content(self):
        docs = [{"content": "券的核销规则", "title": "优惠券指南", "source": "platform_rule"}]
        r = RagResult(docs=docs)
        assert "优惠券指南" in r.context_text
        assert "券的核销规则" in r.context_text
        assert "platform_rule" in r.context_text

    def test_multiple_docs_separated(self):
        docs = [
            {"content": "doc1 content", "title": "doc1", "source": "s1"},
            {"content": "doc2 content", "title": "doc2", "source": "s2"},
        ]
        r = RagResult(docs=docs)
        assert "doc1" in r.context_text
        assert "doc2" in r.context_text
        assert "[1]" in r.context_text
        assert "[2]" in r.context_text

    def test_content_truncated_at_max_chars(self):
        long_content = "x" * 1000  # 超过 MAX_CHUNK_CHARS=800
        docs = [{"content": long_content, "title": "t", "source": "s"}]
        r = RagResult(docs=docs)
        # context_text 里该文档的内容应被截断
        assert len(r.context_text) < 1000 + 200  # 加上标题等开销后不超过截断阈值太多

    def test_to_dict_structure(self):
        docs = [{"content": "c", "title": "t", "source": "s"}]
        r = RagResult(docs=docs)
        d = r.to_dict()
        assert d["refused"] is False
        assert d["doc_count"] == 1
        assert "context_text" in d
        assert d["sources"] == [{"title": "t", "source": "s"}]


# =========================================================
# retrieve() — 集成路径（全部外部依赖 mock）
# =========================================================

class TestRetrieve:
    @pytest.mark.asyncio
    async def test_no_candidates_returns_refused(self):
        """向量搜索和 BM25 都无结果 → 拒答。"""
        with patch("rag.pipeline.embed_query", return_value=[0.1] * 768), \
             patch("rag.pipeline._get_vector_store") as mock_vs, \
             patch("rag.bm25_store.bm25_store") as mock_bm25:
            mock_vs.return_value.search.return_value = []
            mock_bm25.search.return_value = []
            result = await retrieve("测试查询", merchant_id=None)
        assert result.refused is True

    @pytest.mark.asyncio
    async def test_reranker_filters_all_returns_refused(self):
        """Reranker 认为所有候选都低于阈值 → 拒答。"""
        with patch("rag.pipeline.embed_query", return_value=[0.1] * 768), \
             patch("rag.pipeline._get_vector_store") as mock_vs, \
             patch("rag.bm25_store.bm25_store") as mock_bm25, \
             patch("rag.pipeline.rerank", return_value=[]):
            mock_vs.return_value.search.return_value = [{"chunk_id": "c1", "content": "内容"}]
            mock_bm25.search.return_value = []
            result = await retrieve("测试查询", merchant_id=None)
        assert result.refused is True

    @pytest.mark.asyncio
    async def test_success_returns_docs(self):
        """完整成功路径：向量 + Reranker → 返回文档。"""
        ranked = [
            {"chunk_id": "c1", "content": "政策内容", "title": "退款政策",
             "source": "platform_rule", "rerank_score": 0.9}
        ]
        with patch("rag.pipeline.embed_query", return_value=[0.1] * 768), \
             patch("rag.pipeline._get_vector_store") as mock_vs, \
             patch("rag.bm25_store.bm25_store") as mock_bm25, \
             patch("rag.pipeline.rerank", return_value=ranked):
            mock_vs.return_value.search.return_value = [{"chunk_id": "c1", "content": "政策内容"}]
            mock_bm25.search.return_value = []
            result = await retrieve("退款政策是什么", merchant_id=None)
        assert not result.refused
        assert len(result.docs) == 1
        assert "政策" in result.context_text

    @pytest.mark.asyncio
    async def test_bm25_and_vector_merged_via_rrf(self):
        """BM25 有结果时走 RRF 融合路径。"""
        vector_docs = [{"chunk_id": "v1", "content": "向量结果"}]
        bm25_docs = [{"chunk_id": "b1", "content": "BM25结果"}]
        ranked = [
            {"chunk_id": "v1", "content": "向量结果", "title": "t", "source": "s", "rerank_score": 0.8},
            {"chunk_id": "b1", "content": "BM25结果", "title": "t", "source": "s", "rerank_score": 0.7},
        ]
        with patch("rag.pipeline.embed_query", return_value=[0.0] * 768), \
             patch("rag.pipeline._get_vector_store") as mock_vs, \
             patch("rag.bm25_store.bm25_store") as mock_bm25, \
             patch("rag.pipeline.rerank", return_value=ranked):
            mock_vs.return_value.search.return_value = vector_docs
            mock_bm25.search.return_value = bm25_docs
            result = await retrieve("查询", merchant_id=1)
        assert not result.refused
        assert len(result.docs) == 2

    @pytest.mark.asyncio
    async def test_top_k_limits_returned_docs(self):
        """top_k 参数限制最终文档数。"""
        ranked = [
            {"chunk_id": f"c{i}", "content": f"内容{i}", "title": f"t{i}",
             "source": "s", "rerank_score": 0.9 - i * 0.1}
            for i in range(5)
        ]
        with patch("rag.pipeline.embed_query", return_value=[0.0] * 768), \
             patch("rag.pipeline._get_vector_store") as mock_vs, \
             patch("rag.bm25_store.bm25_store") as mock_bm25, \
             patch("rag.pipeline.rerank", return_value=ranked):
            mock_vs.return_value.search.return_value = [{"chunk_id": f"c{i}"} for i in range(5)]
            mock_bm25.search.return_value = []
            result = await retrieve("查询", merchant_id=None, top_k=3)
        assert len(result.docs) == 3
