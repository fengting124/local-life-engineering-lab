import json

from evals.metrics import EvalReport, EvalResult, write_report_artifacts


def test_write_report_artifacts_creates_json_and_markdown(tmp_path):
    report = EvalReport(
        total=1,
        results=[
            EvalResult(
                case_id=1,
                category="query",
                success=True,
                actual_tools=["shop_metrics_query"],
                final_answer="GMV 100 元",
                latency_ms=12,
                total_tokens=99,
                tool_seq_match=1.0,
                task_completed=True,
                keyword_coverage=1.0,
            )
        ],
        tool_call_accuracy=1.0,
        task_completion_rate=1.0,
    )

    json_path, md_path = write_report_artifacts(report, tmp_path, "demo run")

    assert json_path.exists()
    assert md_path.exists()
    assert json.loads(json_path.read_text(encoding="utf-8"))["total"] == 1
    assert "Tool Call Accuracy" in md_path.read_text(encoding="utf-8")
