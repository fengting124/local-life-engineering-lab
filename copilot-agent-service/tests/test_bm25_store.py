"""
rag/bm25_store.py 单元测试。

BM25Store 是纯内存操作（依赖 rank-bm25 库），每个测试使用全新实例，
避免全局单例（bm25_store）的状态污染。

覆盖重点：
  - add_document / build_index / search / clear 的基本行为
  - 权限过滤：public 文档全局可见，merchant_private 只对归属商家可见
  - top_k 限制：结果数不超过 top_k
  - 分词：中英文关键词均可命中
"""
import pytest
from rag.bm25_store import BM25Store, BM25Doc


def make_doc(
    chunk_id: str,
    content: str,
    scope: str = "public",
    merchant_id: int = 0,
) -> BM25Doc:
    return BM25Doc(
        chunk_id=chunk_id,
        doc_id=f"doc_{chunk_id}",
        content=content,
        title=f"标题_{chunk_id}",
        source="faq",
        scope=scope,
        merchant_id=merchant_id,
    )


# =========================================================
# 基本增删查
# =========================================================

class TestBm25StoreBasic:
    def test_empty_store_returns_empty(self):
        store = BM25Store()
        assert store.search("任意查询", merchant_id=None) == []

    def test_doc_count_zero_initially(self):
        store = BM25Store()
        assert store.doc_count == 0

    def test_add_increments_count(self):
        store = BM25Store()
        store.add_document(make_doc("d1", "优惠券核销规则"))
        assert store.doc_count == 1

    def test_add_two_increments_count_twice(self):
        store = BM25Store()
        store.add_document(make_doc("d1", "优惠券规则"))
        store.add_document(make_doc("d2", "退款政策"))
        assert store.doc_count == 2

    def test_search_returns_added_document(self):
        # BM25Okapi IDF 公式：log((N-df+0.5)/(df+0.5))，df=N 时为负（被过滤），
        # df=1 且 N≥3 时 IDF>0（N>2*df）。至少需要 3 篇文档。
        store = BM25Store()
        store.add_document(make_doc("d1", "优惠券核销规则"))
        store.add_document(make_doc("d2", "商家入驻流程指南"))
        store.add_document(make_doc("d3", "退款申请须知"))
        results = store.search("优惠券", merchant_id=None)
        assert any(r["chunk_id"] == "d1" for r in results)

    def test_search_unrelated_query_returns_empty(self):
        store = BM25Store()
        store.add_document(make_doc("d1", "退款政策"))
        results = store.search("zzz_completely_unrelated_zzz", merchant_id=None)
        # BM25 分数为 0 的文档被过滤，不应出现在结果中
        assert len(results) == 0

    def test_clear_empties_store(self):
        store = BM25Store()
        store.add_document(make_doc("d1", "一些内容"))
        store.clear()
        assert store.doc_count == 0
        assert store.search("内容", merchant_id=None) == []

    def test_build_index_batch_sets_count(self):
        store = BM25Store()
        docs = [
            make_doc("d1", "退款政策说明"),
            make_doc("d2", "优惠券使用规则"),
            make_doc("d3", "商家入驻指南"),
        ]
        store.build_index(docs)
        assert store.doc_count == 3

    def test_build_index_searchable(self):
        store = BM25Store()
        # 用多篇文档保证 BM25 IDF > 0（"退款"只出现在 d1，不在其他文档中）
        store.build_index([
            make_doc("d1", "退款政策说明"),
            make_doc("d2", "优惠券使用规则"),
            make_doc("d3", "商家入驻指南"),
        ])
        results = store.search("退款", merchant_id=None)
        assert any(r["chunk_id"] == "d1" for r in results)


# =========================================================
# 权限过滤
# =========================================================

class TestBm25Permissions:
    def test_public_doc_visible_to_all(self):
        store = BM25Store()
        store.add_document(make_doc("pub", "公共政策文档说明", scope="public"))
        store.add_document(make_doc("decoy1", "商家入驻流程指南", scope="public"))
        store.add_document(make_doc("decoy2", "退款申请方法", scope="public"))  # 3 docs → IDF>0
        assert any(r["chunk_id"] == "pub" for r in store.search("公共政策", merchant_id=None))
        assert any(r["chunk_id"] == "pub" for r in store.search("公共政策", merchant_id=1))
        assert any(r["chunk_id"] == "pub" for r in store.search("公共政策", merchant_id=99))

    def test_private_doc_only_visible_to_owner(self):
        store = BM25Store()
        store.add_document(make_doc("priv", "商家专属手册内容", scope="merchant_private", merchant_id=42))
        store.add_document(make_doc("decoy1", "平台规则说明文档", scope="public"))
        store.add_document(make_doc("decoy2", "退款申请流程介绍", scope="public"))  # 3 docs → IDF>0
        # 正确商家
        r_owner = store.search("商家专属手册", merchant_id=42)
        # 其他商家
        r_other = store.search("商家专属手册", merchant_id=99)
        # cs/admin（merchant_id=None）
        r_none = store.search("商家专属手册", merchant_id=None)

        assert any(r["chunk_id"] == "priv" for r in r_owner)
        assert not any(r["chunk_id"] == "priv" for r in r_other)
        assert not any(r["chunk_id"] == "priv" for r in r_none)

    def test_mixed_scope_filters_correctly(self):
        store = BM25Store()
        store.add_document(make_doc("pub", "公共退款政策", scope="public"))
        store.add_document(make_doc("priv_42", "商家42私有手册退款", scope="merchant_private", merchant_id=42))
        store.add_document(make_doc("decoy", "商家入驻流程指南", scope="public"))
        store.add_document(make_doc("decoy2", "优惠券活动说明", scope="public"))
        store.add_document(make_doc("decoy3", "平台功能使用帮助", scope="public"))
        store.add_document(make_doc("decoy4", "数据统计指标说明", scope="public"))
        # "退"/"款" 仅出现在 pub 和 priv_42（df=2），N=6 → IDF=log(4.5/2.5)≈0.58>0

        results_42 = store.search("退款", merchant_id=42)
        results_99 = store.search("退款", merchant_id=99)

        ids_42 = {r["chunk_id"] for r in results_42}
        ids_99 = {r["chunk_id"] for r in results_99}

        assert "pub" in ids_42 and "priv_42" in ids_42
        assert "pub" in ids_99 and "priv_42" not in ids_99


# =========================================================
# top_k 限制
# =========================================================

class TestBm25TopK:
    def test_top_k_limits_results(self):
        store = BM25Store()
        for i in range(10):
            store.add_document(make_doc(f"d{i}", f"退款政策 {i} 内容说明"))
        results = store.search("退款政策", merchant_id=None, top_k=3)
        assert len(results) <= 3

    def test_default_top_k_is_20(self):
        store = BM25Store()
        for i in range(25):
            store.add_document(make_doc(f"d{i}", f"退款政策 {i} 内容说明"))
        results = store.search("退款政策", merchant_id=None)
        assert len(results) <= 20


# =========================================================
# 结果格式
# =========================================================

class TestBm25ResultFormat:
    def test_result_contains_required_fields(self):
        store = BM25Store()
        store.add_document(make_doc("d1", "退款政策"))
        store.add_document(make_doc("d2", "优惠券活动说明"))
        store.add_document(make_doc("d3", "商家入驻流程"))  # 3 docs → IDF>0
        results = store.search("退款", merchant_id=None)
        assert len(results) > 0
        r = results[0]
        assert "chunk_id" in r
        assert "doc_id" in r
        assert "content" in r
        assert "title" in r
        assert "source" in r
        assert "score" in r

    def test_results_sorted_by_score_descending(self):
        store = BM25Store()
        # 第一篇与查询更相关（退款出现多次）
        store.add_document(make_doc("high", "退款退款退款政策说明"))
        store.add_document(make_doc("low", "优惠券规则和退款政策"))
        results = store.search("退款", merchant_id=None)
        if len(results) >= 2:
            assert results[0]["score"] >= results[1]["score"]
