from agent import metrics


class FakeMetric:
    def __init__(self):
        self.label_calls = []
        self.values = []

    def labels(self, **labels):
        self.label_calls.append(labels)
        return self

    def inc(self, value=1):
        self.values.append(value)

    def observe(self, value):
        self.values.append(value)


def test_record_llm_call_rejects_missing_negative_and_boolean_usage(monkeypatch):
    tokens = FakeMetric()
    latency = FakeMetric()
    monkeypatch.setattr(metrics, "llm_tokens_total", tokens)
    monkeypatch.setattr(metrics, "llm_latency_seconds", latency)

    for input_tokens, output_tokens in ((None, 2), (-1, 2), (True, 2), (1, -2)):
        assert metrics.record_llm_call(
            "merchant", input_tokens, output_tokens, 1.0
        ) is False

    assert tokens.values == []
    assert latency.values == []


def test_record_llm_call_normalizes_role_and_never_raises(monkeypatch):
    tokens = FakeMetric()
    latency = FakeMetric()
    monkeypatch.setattr(metrics, "llm_tokens_total", tokens)
    monkeypatch.setattr(metrics, "llm_latency_seconds", latency)

    assert metrics.record_llm_call("unbounded-user-role", 10, 3, 1.25) is True
    assert tokens.label_calls == [
        {"role": "unknown", "token_type": "input"},
        {"role": "unknown", "token_type": "output"},
    ]
    assert tokens.values == [10, 3]
    assert latency.label_calls == [{"role": "unknown"}]
    assert latency.values == [1.25]

    tokens.inc = lambda value=1: (_ for _ in ()).throw(RuntimeError("metric down"))
    assert metrics.record_llm_call("merchant", 1, 1, 0.1) is False


def test_llm_metrics_have_only_low_cardinality_labels():
    assert metrics.llm_tokens_total._labelnames == ("role", "token_type")
    assert metrics.llm_latency_seconds._labelnames == ("role",)
