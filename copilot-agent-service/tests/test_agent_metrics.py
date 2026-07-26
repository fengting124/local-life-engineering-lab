from agent import metrics


def _counter_value(counter, **labels) -> float:
    samples = counter.collect()[0].samples
    return next(
        (
            sample.value
            for sample in samples
            if sample.name.endswith("_total") and sample.labels == labels
        ),
        0.0,
    )


def test_policy_metric_normalizes_untrusted_tool_and_role_labels():
    before = _counter_value(
        metrics.agent_tool_policy_denied_total,
        tool="unknown",
        role="unknown",
    )

    metrics.record_tool_policy_denied(
        "model_invented_tool_with_unbounded_name",
        "attacker_supplied_role",
    )

    assert _counter_value(
        metrics.agent_tool_policy_denied_total,
        tool="unknown",
        role="unknown",
    ) == before + 1
    samples = metrics.agent_tool_policy_denied_total.collect()[0].samples
    assert all(
        sample.labels.get("tool") != "model_invented_tool_with_unbounded_name"
        for sample in samples
    )


def test_budget_metric_normalizes_untrusted_reason_and_tool_labels():
    before = _counter_value(
        metrics.agent_tool_budget_exhausted_total,
        reason="unknown",
        tool="unknown",
    )

    metrics.record_tool_budget_exhausted(
        "model_invented_reason",
        "model_invented_tool",
    )

    assert _counter_value(
        metrics.agent_tool_budget_exhausted_total,
        reason="unknown",
        tool="unknown",
    ) == before + 1
