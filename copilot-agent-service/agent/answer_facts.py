"""Render controlled diagnostic answers from bounded tool evidence."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping


STATUS_TEXT: dict[str, dict[object, str]] = {
    "order_status": {
        "WAIT_PAY": "待支付",
        "PAID": "已支付",
        "COMPLETED": "已完成",
        "CANCELLED": "已取消",
        "REFUNDED": "已退款",
    },
    "payment_status": {
        "PENDING": "支付处理中",
        "SUCCESS": "支付成功",
        "FAILED": "支付失败",
        "CLOSED": "支付已关闭",
    },
    "coupon_issue_status": {
        "PENDING": "发券处理中",
        "SENT": "已发券",
        "FAILED": "发券失败",
        "NO_RECORD": "无发券记录",
    },
    "coupon_usage_status": {
        "UNUSED": "未使用",
        "USED": "已使用",
        "EXPIRED": "已过期",
        "NONE": "未发放",
    },
    "mq_dead_letter_present": {
        True: "存在",
        False: "不存在",
    },
}

FACT_LABELS = {
    "order_status": "订单状态",
    "payment_status": "支付状态",
    "coupon_issue_status": "发券状态",
    "coupon_usage_status": "优惠券状态",
    "mq_dead_letter_present": "MQ 死信",
}
STATUS_ALIASES: dict[tuple[str, object], tuple[str, ...]] = {
    ("payment_status", "SUCCESS"): ("支付已经成功",),
}


def _markers(key: str, value: object) -> tuple[str, ...]:
    return (
        str(value).lower(),
        STATUS_TEXT[key][value].lower(),
        *(marker.lower() for marker in STATUS_ALIASES.get((key, value), ())),
    )


@dataclass(frozen=True)
class AnswerFact:
    key: str
    value: object
    text: str

    def appears_in(self, candidate: str) -> bool:
        normalized = candidate.lower()
        return any(marker in normalized for marker in _markers(self.key, self.value))

    def is_contradicted_by(self, candidate: str) -> bool:
        normalized = candidate.lower()
        return any(
            value != self.value
            and any(marker in normalized for marker in _markers(self.key, value))
            for value in STATUS_TEXT[self.key]
        )


@dataclass(frozen=True)
class EvidenceAnswer:
    facts: tuple[AnswerFact, ...]

    def render(self) -> str:
        return "；".join(
            f"{FACT_LABELS[fact.key]}：{fact.text}" for fact in self.facts
        ) + "。"


def _successful_facts(
    evidence: object,
    tool_name: str,
) -> Mapping[str, object] | None:
    if not isinstance(evidence, Mapping):
        return None
    record = evidence.get(tool_name)
    if not isinstance(record, Mapping) or record.get("status") != "success":
        return None
    facts = record.get("facts")
    return facts if isinstance(facts, Mapping) else None


def _answer_fact(
    facts: Mapping[str, object],
    key: str,
) -> AnswerFact | None:
    value = facts.get(key)
    text = STATUS_TEXT[key].get(value)
    return AnswerFact(key=key, value=value, text=text) if text is not None else None


def build_evidence_answer(state: Mapping[str, object]) -> EvidenceAnswer | None:
    """Build a complete answer for supported, completed diagnostic routes."""
    if state.get("synthesis_only") is not True:
        return None

    task_type = state.get("route_task_type")
    evidence = state.get("evidence_collected")
    order = _successful_facts(evidence, "query_order")
    if order is None:
        return None
    order_status = _answer_fact(order, "order_status")
    if order_status is None:
        return None

    facts: list[AnswerFact] = [order_status]
    if task_type == "payment_diagnosis":
        payment = _successful_facts(evidence, "query_payment")
        payment_status = (
            _answer_fact(payment, "payment_status")
            if payment is not None
            else None
        )
        if payment_status is None:
            return None
        facts.append(payment_status)
    elif task_type in {"coupon_issue", "coupon_root_cause"}:
        coupon = _successful_facts(evidence, "query_coupon_issue_log")
        issue_status = (
            _answer_fact(coupon, "coupon_issue_status")
            if coupon is not None
            else None
        )
        if issue_status is None:
            return None
        facts.append(issue_status)
        usage_status = _answer_fact(coupon, "coupon_usage_status")
        if usage_status is not None:
            facts.append(usage_status)
        dead_letter = _successful_facts(evidence, "query_mq_dead_letter")
        if dead_letter is not None:
            mq_status = _answer_fact(dead_letter, "mq_dead_letter_present")
            if mq_status is not None:
                facts.append(mq_status)
    elif task_type == "mq_diagnosis":
        dead_letter = _successful_facts(evidence, "query_mq_dead_letter")
        mq_status = (
            _answer_fact(dead_letter, "mq_dead_letter_present")
            if dead_letter is not None
            else None
        )
        if mq_status is None:
            return None
        facts.append(mq_status)
    else:
        return None

    return EvidenceAnswer(facts=tuple(facts))


def validate_or_fallback(
    candidate: object,
    answer: EvidenceAnswer | None,
) -> str:
    """Keep a complete supported answer; otherwise return deterministic facts."""
    if answer is None:
        return str(candidate or "")
    if not isinstance(candidate, str) or not candidate.strip():
        return answer.render()
    if any(
        not fact.appears_in(candidate) or fact.is_contradicted_by(candidate)
        for fact in answer.facts
    ):
        return answer.render()
    return candidate
