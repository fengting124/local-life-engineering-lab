from evals import rag_benchmark
from evals.rag_benchmark import (
    DEFAULT_CASES,
    RagBenchmarkCase,
    RetrievalRun,
    _extract_answer,
    _lexical_score,
    evaluate_cases,
    offline_retriever,
)


def _doc(doc_id: str, title: str = "") -> dict:
    return {"doc_id": doc_id, "title": title or doc_id, "source": "test"}


def test_evaluate_cases_reports_recall_rerank_citation_and_refusal():
    cases = [
        RagBenchmarkCase(
            case_id="refund_rule",
            question="退款需要审批吗",
            expected_docs=["platform_rules"],
            expected_answer_terms=["HITL", "审批"],
        ),
        RagBenchmarkCase(
            case_id="refuse_secret",
            question="internal key 是多少",
            expected_docs=[],
            expected_answer_terms=[],
            should_refuse=True,
        ),
    ]
    runs = {
        "refund_rule": RetrievalRun(
            before=[_doc("wrong"), _doc("platform_rules")],
            after=[_doc("platform_rules"), _doc("wrong")],
            answer="退款必须经过 HITL 审批。",
            refused=False,
        ),
        "refuse_secret": RetrievalRun(before=[], after=[], answer="", refused=True),
    }

    report = evaluate_cases(cases, lambda case: runs[case.case_id])

    assert report["summary"]["recall_at_5_before"] == 1.0
    assert report["summary"]["recall_at_5_after"] == 1.0
    assert report["summary"]["citation_accuracy"] == 1.0
    assert report["summary"]["refusal_accuracy"] == 1.0
    assert report["cases"][0]["rerank_delta"] > 0


def test_evaluate_cases_marks_missing_expected_doc_as_failed():
    cases = [
        RagBenchmarkCase(
            case_id="coupon_issue",
            question="支付成功但券未发放怎么办",
            expected_docs=["troubleshooting_cases"],
            expected_answer_terms=["补偿券"],
        )
    ]
    run = RetrievalRun(before=[_doc("platform_rules")], after=[_doc("platform_rules")], answer="不知道", refused=False)

    report = evaluate_cases(cases, lambda _: run)

    assert report["summary"]["recall_at_5_after"] == 0.0
    assert report["summary"]["citation_accuracy"] == 0.0


def test_expected_doc_can_match_ingested_source_alias():
    cases = [
        RagBenchmarkCase(
            case_id="refund_rule",
            question="退款需要审批吗",
            expected_docs=["platform_rules", "platform_rule"],
            expected_answer_terms=["HITL", "审批"],
        )
    ]
    run = RetrievalRun(
        before=[{"doc_id": "chunk-1", "title": "LocalLife 平台规则手册", "source": "platform_rule"}],
        after=[{"doc_id": "chunk-1", "title": "LocalLife 平台规则手册", "source": "platform_rule"}],
        answer="退款必须经过 HITL 审批。",
        refused=False,
    )

    report = evaluate_cases(cases, lambda _: run)

    assert report["summary"]["recall_at_5_after"] == 1.0
    assert report["summary"]["citation_accuracy"] == 1.0


def test_lexical_score_matches_chinese_by_character():
    assert _lexical_score("退款审批", "已支付订单退款必须经过人工审批") > 0


def test_default_benchmark_has_representative_unique_cases():
    case_ids = [case.case_id for case in DEFAULT_CASES]
    refusal_cases = [case for case in DEFAULT_CASES if case.should_refuse]
    positive_cases = [case for case in DEFAULT_CASES if not case.should_refuse]

    assert 20 <= len(DEFAULT_CASES) <= 30
    assert len(case_ids) == len(set(case_ids))
    assert len(refusal_cases) >= 4
    assert all(case.expected_docs for case in positive_cases)
    assert all(case.expected_answer_terms for case in positive_cases)


def test_refusal_is_measured_from_retrieval_instead_of_expected_label(monkeypatch):
    case = RagBenchmarkCase(
        case_id="false_positive",
        question="secret deployment token",
        expected_docs=[],
        expected_answer_terms=[],
        should_refuse=True,
    )
    monkeypatch.setattr(
        rag_benchmark,
        "_load_kb_docs",
        lambda: [{
            "chunk_id": "leaky",
            "doc_id": "leaky",
            "title": "secret deployment token",
            "source": "test",
            "content": "secret deployment token must not be retrieved",
        }],
    )

    result = offline_retriever(case)

    assert result.refused is False


def test_extract_answer_keeps_evidence_for_each_expected_term():
    content = "\n".join([
        "MQ first",
        "MQ second",
        "MQ third",
        "MQ fourth",
        "MQ fifth",
        "Canal synchronization",
        "difference alarm is 1%",
    ])

    answer = _extract_answer(content, ["MQ", "Canal", "1%"])

    assert "MQ" in answer
    assert "Canal" in answer
    assert "1%" in answer
