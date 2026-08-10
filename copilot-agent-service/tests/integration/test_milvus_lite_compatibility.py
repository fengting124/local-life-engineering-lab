from importlib.metadata import version

from rag import vector_store as vector_store_module
from rag.vector_store import MilvusVectorStore


def _vector(index: int) -> list[float]:
    value = [0.0] * 768
    value[index] = 1.0
    return value


def _document(
    chunk_id: str,
    doc_id: str,
    *,
    scope: str,
    merchant_id: int,
    vector_index: int,
) -> dict:
    return {
        "chunk_id": chunk_id,
        "doc_id": doc_id,
        "content": f"fixture content {doc_id}",
        "embedding": _vector(vector_index),
        "scope": scope,
        "merchant_id": merchant_id,
        "source": "compatibility-test",
        "title": f"fixture {doc_id}",
    }


def test_milvus_lite_2_4_persists_schema_filters_search_and_delete(
    tmp_path,
    monkeypatch,
):
    assert version("pymilvus") == "2.4.9"
    monkeypatch.setattr(vector_store_module.rag_config, "embedding_dimension", 768)
    db_path = tmp_path / "local_life_kb.db"
    collection = "local_life_kb"
    store = MilvusVectorStore(str(db_path), collection)

    assert store.upsert(
        [
            _document(
                "chunk-public",
                "doc-public",
                scope="public",
                merchant_id=0,
                vector_index=0,
            ),
            _document(
                "chunk-m7",
                "doc-m7",
                scope="merchant_private",
                merchant_id=7,
                vector_index=0,
            ),
            _document(
                "chunk-m8",
                "doc-m8",
                scope="merchant_private",
                merchant_id=8,
                vector_index=0,
            ),
        ]
    ) == 3

    schema = store._client.describe_collection(collection)
    fields = {field["name"]: field for field in schema["fields"]}
    assert set(fields) == {
        "chunk_id",
        "doc_id",
        "content",
        "embedding",
        "scope",
        "merchant_id",
        "source",
        "title",
    }
    assert fields["embedding"]["params"]["dim"] == 768

    public_ids = {item["doc_id"] for item in store.search(_vector(0), None, 10)}
    merchant_ids = {item["doc_id"] for item in store.search(_vector(0), 7, 10)}
    assert public_ids == {"doc-public"}
    assert merchant_ids == {"doc-public", "doc-m7"}

    store._client.close()
    restarted = MilvusVectorStore(str(db_path), collection)
    after_restart = {
        item["doc_id"] for item in restarted.search(_vector(0), 7, 10)
    }
    assert after_restart == {"doc-public", "doc-m7"}

    assert restarted.delete_by_doc_id("doc-m7") == 1
    after_delete = {
        item["doc_id"] for item in restarted.search(_vector(0), 7, 10)
    }
    assert after_delete == {"doc-public"}
    restarted._client.close()
    assert db_path.exists()
