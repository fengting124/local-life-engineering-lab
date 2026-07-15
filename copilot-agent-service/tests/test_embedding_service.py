import importlib.util
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def _load_embedding_app():
    app_path = Path(__file__).resolve().parents[1] / "model_services" / "embedding_service" / "app.py"
    spec = importlib.util.spec_from_file_location("embedding_service_app", app_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class _FakeEmbeddings:
    def __init__(self, count: int):
        self.shape = (count, 3)
        self._rows = [[0.1, 0.2, 0.3] for _ in range(count)]

    def tolist(self):
        return self._rows


class _NonReentrantModel:
    def __init__(self):
        self._lock = threading.Lock()
        self._in_use = False

    def encode(self, texts, **_kwargs):
        with self._lock:
            if self._in_use:
                raise RuntimeError("Already borrowed")
            self._in_use = True

        try:
            time.sleep(0.05)
            return _FakeEmbeddings(len(texts))
        finally:
            with self._lock:
                self._in_use = False


def test_embed_serializes_model_encode_calls(monkeypatch):
    embedding_app = _load_embedding_app()
    monkeypatch.setattr(embedding_app, "_model", _NonReentrantModel())
    monkeypatch.setattr(embedding_app, "_device", "cpu")

    request = embedding_app.EmbedRequest(texts=["query: 今天销售额是多少"])
    start = threading.Barrier(2)

    def call_embed():
        start.wait(timeout=1)
        return embedding_app.embed(request)

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(call_embed) for _ in range(2)]
        responses = [future.result(timeout=2) for future in futures]

    assert [response.count for response in responses] == [1, 1]
