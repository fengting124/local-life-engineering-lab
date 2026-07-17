"""向量存储配置和工厂测试。"""
import os
from pathlib import Path
from unittest.mock import patch

import pytest

from rag.config import load_rag_config
from rag.vector_store import (
    _is_local_file_uri,
    _prepare_connection_environment,
    _without_pymilvus_env_uri_for_local_file,
)
from rag.vector_store_factory import create_vector_store


MILVUS_ENV_KEYS = (
    "VECTOR_BACKEND",
    "MILVUS_URI",
    "MILVUS_HOST",
    "MILVUS_PORT",
)


def _clear_milvus_env(monkeypatch):
    for key in MILVUS_ENV_KEYS:
        monkeypatch.delenv(key, raising=False)


def test_default_config_uses_milvus_lite_file(monkeypatch):
    _clear_milvus_env(monkeypatch)

    config = load_rag_config()

    assert config.vector_backend == "milvus"
    assert config.milvus_uri == "./data/local_life_kb.db"


def test_explicit_uri_has_priority_over_legacy_host_port(monkeypatch):
    _clear_milvus_env(monkeypatch)
    monkeypatch.setenv("MILVUS_URI", "./tmp/test.db")
    monkeypatch.setenv("MILVUS_HOST", "ignored-host")
    monkeypatch.setenv("MILVUS_PORT", "29999")

    config = load_rag_config()

    assert config.milvus_uri == "./tmp/test.db"


def test_legacy_host_port_still_builds_standalone_uri(monkeypatch):
    _clear_milvus_env(monkeypatch)
    monkeypatch.setenv("MILVUS_HOST", "milvus")
    monkeypatch.setenv("MILVUS_PORT", "19530")

    config = load_rag_config()

    assert config.milvus_uri == "http://milvus:19530"


def test_factory_passes_unified_uri(monkeypatch):
    _clear_milvus_env(monkeypatch)
    monkeypatch.setenv("MILVUS_URI", "./data/factory-test.db")
    config = load_rag_config()

    with patch("rag.vector_store_factory.MilvusVectorStore") as store_cls:
        instance = create_vector_store(config)

    assert instance is store_cls.return_value
    store_cls.assert_called_once_with(
        uri="./data/factory-test.db",
        collection_name="local_life_kb",
    )


def test_unknown_backend_fails_closed(monkeypatch):
    _clear_milvus_env(monkeypatch)
    monkeypatch.setenv("VECTOR_BACKEND", "typo-backend")
    config = load_rag_config()

    with pytest.raises(ValueError, match="Unsupported VECTOR_BACKEND"):
        create_vector_store(config)


def test_local_uri_creates_parent_directory(tmp_path):
    db_path = tmp_path / "nested" / "knowledge.db"

    prepared = _prepare_connection_environment(str(db_path))

    assert prepared == str(db_path)
    assert db_path.parent.is_dir()
    assert _is_local_file_uri(prepared)


def test_http_uri_is_not_treated_as_local_file():
    assert not _is_local_file_uri("http://milvus:19530")


def test_local_file_uri_is_hidden_from_pymilvus_import(monkeypatch):
    monkeypatch.setenv("MILVUS_URI", "/app/data/local_life_kb.db")

    with _without_pymilvus_env_uri_for_local_file("/app/data/local_life_kb.db"):
        assert "MILVUS_URI" not in os.environ

    assert os.environ["MILVUS_URI"] == "/app/data/local_life_kb.db"


def test_remote_uri_keeps_milvus_env_for_pymilvus(monkeypatch):
    monkeypatch.setenv("MILVUS_URI", "http://milvus:19530")

    with _without_pymilvus_env_uri_for_local_file("http://milvus:19530"):
        assert os.environ["MILVUS_URI"] == "http://milvus:19530"
