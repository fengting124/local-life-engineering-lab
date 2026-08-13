"""Deterministic, role-filtered tool route decisions for the current request."""
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import hashlib
import re
from typing import Mapping, Sequence

import structlog


log = structlog.get_logger(__name__)


# All tool-role permissions remain centralized here and shared with execution.
TOOL_ROLE_MAP: dict[str, list[str]] = {
    "query_order": ["merchant", "cs", "admin"],
    "query_payment": ["admin"],
    "query_coupon_issue_log": ["admin"],
    "query_mq_dead_letter": ["admin"],
    "resolve_compensation_coupon": ["admin"],
    "shop_metrics_query": ["merchant", "admin"],
    "coupon_policy_lookup": ["merchant", "admin"],
    "campaign_draft_generate": ["merchant", "admin"],
    "execute_refund": ["cs", "admin"],
    "issue_compensation_coupon": ["cs", "admin"],
    "knowledge_search": ["merchant", "admin"],
}


def is_tool_allowed_for_role(tool_name: str, user_role: str) -> bool:
    """Return whether a registered tool is available to the role."""
    return user_role in TOOL_ROLE_MAP.get(tool_name, ())


# Tools not explicitly declared safe remain serialized by default.
TOOL_CONCURRENCY_SAFE: set[str] = {
    "query_order",
    "query_payment",
    "query_coupon_issue_log",
    "query_mq_dead_letter",
    "resolve_compensation_coupon",
    "shop_metrics_query",
    "coupon_policy_lookup",
    "knowledge_search",
}


def is_tool_concurrency_safe(tool_name: str) -> bool:
    """Return whether a tool may be run concurrently (fail closed)."""
    return tool_name in TOOL_CONCURRENCY_SAFE


ROUTE_MODES = {"controlled", "clarification", "general_fallback"}
GENERAL_READ_ONLY_TOOLS = (
    "query_order",
    "shop_metrics_query",
    "knowledge_search",
    "coupon_policy_lookup",
)
TASK_TOOL_PLANS: dict[str, tuple[str, ...]] = {
    "analytics": ("shop_metrics_query",),
    "order_query": ("query_order",),
    "payment_diagnosis": ("query_order", "query_payment"),
    "coupon_issue": ("query_order", "query_coupon_issue_log"),
    "coupon_root_cause": (
        "query_order",
        "query_coupon_issue_log",
        "query_mq_dead_letter",
    ),
    "mq_diagnosis": ("query_order", "query_mq_dead_letter"),
    "knowledge": ("knowledge_search",),
    "policy_configuration": ("knowledge_search", "coupon_policy_lookup"),
    "refund_action": ("query_order", "execute_refund"),
    "compensation_action": (
        "query_order",
        "resolve_compensation_coupon",
        "issue_compensation_coupon",
    ),
}
STRONG_EXECUTION_TERMS = ("执行", "发起", "进行", "办理", "操作")
WEAK_EXECUTION_TERMS = ("申请", "给我", "帮我", "帮忙", "帮", "给", "为", "对")
HIGH_RISK_QUERY_TERMS = ("查", "查询", "规则", "政策", "状态", "情况", "进度", "进展")
HIGH_RISK_INTERROGATIVE_TERMS = ("如何", "怎么", "是否", "能否", "可以")
HIGH_RISK_QUESTION_MARKERS = ("吗", "？", "?")
ACTION_CLAUSE_SEPARATORS = (",", "，", "。", ";", "；", "!", "！")
ACTION_SEQUENCE_TERMS = ("后", "之后", "然后")
REFUND_ACTION_TERMS = ("退款", "退钱", "退回")
COMPENSATION_ACTION_TERMS = (
    "补券",
    "补发券",
    "补发优惠券",
    "赔付券",
    "补偿券",
)
_CURRENCY_AMOUNT_PATTERN = re.compile(
    r"(?:"
    r"[¥￥]\s*([+-]?\s*\d+(?:\.\d+)?)(?![\d.])"
    r"|(?<![\d.])([+-]?\s*\d+(?:\.\d+)?)\s*(?:元|块|人民币)(?![\d.])"
    r")",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class RouteDecision:
    task_type: str
    route_mode: str
    confidence: int
    required_tools: tuple[str, ...] = ()
    authorized_tools: tuple[str, ...] = ()
    next_tool: str | None = None
    missing_fields: tuple[str, ...] = ()
    target_order_hash: str | None = None
    requested_amount_minor: int | None = None

    def to_state(self) -> dict[str, object]:
        return {
            "route_task_type": self.task_type,
            "route_mode": self.route_mode,
            "route_confidence": self.confidence,
            "route_required_tools": list(self.required_tools),
            "route_authorized_tools": list(self.authorized_tools),
            "route_next_tool": self.next_tool,
            "route_missing_fields": list(self.missing_fields),
            "route_target_order_hash": self.target_order_hash,
            "route_requested_amount_minor": self.requested_amount_minor,
        }

    @classmethod
    def from_state(cls, state: Mapping[str, object]) -> "RouteDecision":
        route_mode = str(state.get("route_mode", "clarification"))
        if route_mode not in ROUTE_MODES:
            route_mode = "clarification"
        return cls(
            task_type=str(state.get("route_task_type", "unknown")),
            route_mode=route_mode,
            confidence=int(state.get("route_confidence", 0)),
            required_tools=tuple(state.get("route_required_tools", ())),
            authorized_tools=tuple(state.get("route_authorized_tools", ())),
            next_tool=state.get("route_next_tool"),
            missing_fields=tuple(state.get("route_missing_fields", ())),
            target_order_hash=state.get("route_target_order_hash"),
            requested_amount_minor=state.get("route_requested_amount_minor"),
        )


def _decision(
    user_role: str,
    task_type: str,
    score: int,
    *,
    route_mode: str = "controlled",
    required_tools: Sequence[str] = (),
    missing_fields: Sequence[str] = (),
    target_order_hash: str | None = None,
    requested_amount_minor: int | None = None,
) -> RouteDecision:
    required = tuple(required_tools)
    authorized = tuple(
        name for name in required if is_tool_allowed_for_role(name, user_role)
    )
    first = required[0] if required and required[0] in authorized else None
    return RouteDecision(
        task_type=task_type,
        route_mode=route_mode,
        confidence=min(score, 100),
        required_tools=required,
        authorized_tools=authorized,
        next_tool=first,
        missing_fields=tuple(missing_fields),
        target_order_hash=target_order_hash,
        requested_amount_minor=requested_amount_minor,
    )


def _contains_any(text: str, terms: Sequence[str]) -> bool:
    return any(term in text for term in terms)


def _order_ids(text: str) -> tuple[str, ...]:
    matches = re.findall(r"(?<!\d)\d{12,}(?!\d)", text)
    return tuple(dict.fromkeys(matches))


def order_target_hash(value: object) -> str | None:
    """Hash a normalized order number without retaining it in route state."""
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        return None
    normalized = str(value).strip()
    if not normalized:
        return None
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _requested_amounts_minor(
    text: str,
    action_terms: Sequence[str],
) -> tuple[int, ...]:
    action_start = min(
        (text.find(term) for term in action_terms if term in text),
        default=-1,
    )
    if action_start < 0:
        return ()

    amounts: list[int] = []
    for match in _CURRENCY_AMOUNT_PATTERN.finditer(text, action_start):
        raw_amount = re.sub(r"\s+", "", match.group(1) or match.group(2))
        try:
            amount = Decimal(raw_amount)
        except (InvalidOperation, TypeError):
            return ()
        minor = amount * 100
        if amount <= 0 or minor != minor.to_integral_value():
            return ()
        amounts.append(int(minor))
    return tuple(dict.fromkeys(amounts))


def _has_high_risk_query(text: str) -> bool:
    return _contains_any(
        text,
        (
            *HIGH_RISK_QUERY_TERMS,
            *HIGH_RISK_INTERROGATIVE_TERMS,
            *HIGH_RISK_QUESTION_MARKERS,
        ),
    )


def _strong_action_clause_is_query(text: str, start: int, end: int) -> bool:
    separator_pattern = "|".join(
        re.escape(term) for term in ACTION_CLAUSE_SEPARATORS
    )
    prefix = text[:start]
    clause_prefix = re.split(separator_pattern, prefix)[-1]
    if _contains_any(clause_prefix, HIGH_RISK_INTERROGATIVE_TERMS):
        return True

    query_position = max(
        (clause_prefix.rfind(term) for term in ("查", "查询")),
        default=-1,
    )
    sequence_position = max(
        (clause_prefix.rfind(term) for term in ACTION_SEQUENCE_TERMS),
        default=-1,
    )
    if query_position >= 0 and sequence_position < query_position:
        return True

    clause_suffix = re.split(separator_pattern, text[end:], maxsplit=1)[0]
    return _contains_any(
        clause_suffix,
        (
            *HIGH_RISK_QUERY_TERMS,
            *HIGH_RISK_INTERROGATIVE_TERMS,
            *HIGH_RISK_QUESTION_MARKERS,
        ),
    )


def _has_high_risk_execution(text: str, action_terms: Sequence[str]) -> bool:
    action_pattern = "|".join(re.escape(term) for term in action_terms)
    strong_pattern = "|".join(re.escape(term) for term in STRONG_EXECUTION_TERMS)
    strong_actions = re.finditer(
        rf"(?:{strong_pattern}).{{0,24}}(?:{action_pattern})",
        text,
    )
    if any(
        not _strong_action_clause_is_query(text, match.start(), match.end())
        for match in strong_actions
    ):
        return True
    if _has_high_risk_query(text):
        return False
    if re.search(
        rf"(?:{action_pattern}).{{0,32}}"
        rf"(?:(?<!申)请(?:帮助|帮忙)?|帮我|帮忙|帮助).{{0,4}}(?:处理|办理)",
        text,
    ):
        return True
    weak_pattern = "|".join(re.escape(term) for term in WEAK_EXECUTION_TERMS)
    return bool(re.search(rf"(?:{weak_pattern}).{{0,24}}(?:{action_pattern})", text))


def _clarification(user_role: str, task_type: str, score: int, field: str) -> RouteDecision:
    return _decision(
        user_role,
        task_type,
        score,
        route_mode="clarification",
        missing_fields=(field,),
    )


def _campaign_plan(text: str) -> tuple[str, ...]:
    has_threshold = bool(re.search(r"(?:满|门槛|阈值|threshold)\s*\d+", text))
    has_validity = bool(
        re.search(
            r"(?:有效期|validity)\s*(?:为|至|到|:|：)?\s*(?:\d+\s*(?:天|日)|\d{4}[-/]\d{1,2}[-/]\d{1,2})",
            text,
        )
    )
    has_purchase_limit = bool(
        re.search(
            r"(?:每人(?:限购|最多)?|限购|purchase limit)\s*(?:为|:|：)?\s*\d+",
            text,
        )
    )
    if has_threshold and has_validity and has_purchase_limit:
        return ("campaign_draft_generate",)
    return ("coupon_policy_lookup", "campaign_draft_generate")


def classify_request(user_role: str, message: str) -> RouteDecision:
    """Classify one user request without consulting conversation or tool output."""
    text = re.sub(r"\s+", " ", message.lower()).strip()
    order_ids = _order_ids(text)
    has_one_order = len(order_ids) == 1
    has_order_reference = has_one_order or _contains_any(text, ("订单", "order"))

    has_campaign_object = _contains_any(text, ("活动", "优惠券", "campaign", "coupon"))
    knowledge_terms = (
        "规则",
        "政策",
        "限制",
        "区别",
        "比例",
        "sla",
        "时限",
        "什么是",
        "怎么",
        "为何",
    )
    has_question = _contains_any(text, ("？", "?", "什么", "为什么", "如何", "怎么", "吗"))
    has_knowledge = (
        _contains_any(text, knowledge_terms) and "怎么样" not in text
    ) or (
        has_campaign_object
        and has_question
        and _contains_any(text, ("发布", "申请", "提前"))
    )
    has_metric = _contains_any(
        text,
        ("订单量", "多少笔订单", "多少单", "gmv", "营业额", "销售额", "交易额", "卖了多少", "多少钱"),
    ) or bool(re.search(r"核销.*(?:多少|几|张|笔|量)", text))
    has_aggregate = has_metric or _contains_any(text, ("数据", "统计", "总共", "汇总"))
    has_month_to_date = _contains_any(text, ("这个月", "本月", "这月", "this month"))
    has_supported_date = has_month_to_date or _contains_any(
        text, ("今天", "昨日", "昨天", "today", "yesterday")
    ) or bool(
        re.search(r"\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{4}年\d{1,2}月\d{1,2}日", text)
    )
    has_unsupported_date = _contains_any(
        text,
        ("这周", "本周", "最近", "范围", "区间"),
    )
    analytics_score = (
        (60 if has_aggregate else 0)
        + (30 if has_metric else 0)
        + (20 if has_supported_date else 0)
    )
    analytics_intent = has_aggregate or has_metric or has_unsupported_date

    refund_intent = _has_high_risk_execution(text, REFUND_ACTION_TERMS)
    compensation_action_text = re.sub(
        r"补发.{0,16}优惠券",
        "补发优惠券",
        text,
    )
    compensation_intent = _has_high_risk_execution(
        compensation_action_text,
        COMPENSATION_ACTION_TERMS,
    )

    has_mq = _contains_any(text, ("mq", "消息队列", "死信", "dead letter", "消费失败", "消费者失败"))
    has_coupon_issue = _contains_any(
        text,
        ("没收到券", "没发券", "未发券", "发券失败", "优惠券异常", "券异常", "库存不足"),
    )
    has_root_cause = _contains_any(text, ("根因", "根本原因", "查原因", "为什么"))
    has_explicit_payment_issue = _contains_any(
        text,
        ("支付失败", "支付异常", "支付状态", "支付情况", "支付回调", "支付不一致"),
    ) or bool(re.search(r"支付(?:和|与).{0,12}异常", text)) or (
        _contains_any(text, ("已支付", "支付成功", "paid"))
        and _contains_any(text, ("待支付", "未支付", "unpaid", "pending payment"))
    )
    has_payment_issue = has_explicit_payment_issue
    has_campaign_verb = _contains_any(text, ("创建", "新建", "生成", "草拟", "起草", "draft"))
    campaign_intent = has_campaign_object and has_campaign_verb
    configuration_intent = (
        _contains_any(text, ("配置", "设置", "设定"))
        and _contains_any(text, ("配置", "门槛", "限购", "阈值", "有效期"))
        and not campaign_intent
    )
    order_query_intent = has_order_reference and _contains_any(
        text, ("查", "查询", "状态", "详情", "情况", "进度", "进展", "订单")
    )

    if refund_intent:
        if not has_one_order:
            return _clarification(user_role, "refund_action", 100, "order_id")
        requested_amounts = _requested_amounts_minor(text, REFUND_ACTION_TERMS)
        if len(requested_amounts) != 1:
            return _clarification(user_role, "refund_action", 100, "amount")
        return _decision(
            user_role,
            "refund_action",
            100,
            required_tools=TASK_TOOL_PLANS["refund_action"],
            target_order_hash=order_target_hash(order_ids[0]),
            requested_amount_minor=requested_amounts[0],
        )
    if compensation_intent:
        if not has_one_order:
            return _clarification(user_role, "compensation_action", 100, "order_id")
        requested_amounts = _requested_amounts_minor(
            text,
            (*COMPENSATION_ACTION_TERMS, "补发"),
        )
        if len(requested_amounts) != 1:
            return _clarification(
                user_role,
                "compensation_action",
                100,
                "amount",
            )
        return _decision(
            user_role,
            "compensation_action",
            100,
            required_tools=TASK_TOOL_PLANS["compensation_action"],
            target_order_hash=order_target_hash(order_ids[0]),
            requested_amount_minor=requested_amounts[0],
        )

    diagnostic_scores: dict[str, int] = {}
    if has_mq:
        diagnostic_scores["mq_diagnosis"] = 90
    if has_coupon_issue:
        diagnostic_scores["coupon_root_cause" if has_root_cause else "coupon_issue"] = (
            100 if has_root_cause else 80
        )
    if has_payment_issue:
        diagnostic_scores["payment_diagnosis"] = 80

    if diagnostic_scores and not has_one_order:
        task_type, score = max(diagnostic_scores.items(), key=lambda item: item[1])
        return _clarification(user_role, task_type, score, "order_id")

    scored_families: dict[str, int] = dict(diagnostic_scores)
    if analytics_intent:
        scored_families["analytics"] = analytics_score
    if (has_knowledge or configuration_intent) and not campaign_intent:
        scored_families["policy_configuration" if configuration_intent else "knowledge"] = (
            60 + (20 if has_question else 0) + (20 if not has_one_order else 0) + (30 if configuration_intent else 0)
        )
    if campaign_intent:
        scored_families["campaign_draft"] = 100
    if (
        order_query_intent
        and not diagnostic_scores
        and not analytics_intent
        and not has_knowledge
    ):
        scored_families["order_query"] = 50 + (30 if has_order_reference else 0)

    unrelated_scores = sorted(scored_families.values(), reverse=True)
    if (
        len(unrelated_scores) >= 2
        and unrelated_scores[0] >= 60
        and unrelated_scores[1] >= 60
        and unrelated_scores[0] - unrelated_scores[1] < 20
    ):
        return _decision(user_role, "unknown", unrelated_scores[0], route_mode="general_fallback")

    for task_type in (
        "mq_diagnosis",
        "coupon_root_cause",
        "coupon_issue",
        "payment_diagnosis",
    ):
        if task_type in diagnostic_scores:
            return _decision(
                user_role,
                task_type,
                diagnostic_scores[task_type],
                required_tools=TASK_TOOL_PLANS[task_type],
            )

    if analytics_intent:
        if not has_metric:
            return _clarification(user_role, "analytics", analytics_score, "metric")
        if has_unsupported_date:
            return _clarification(user_role, "analytics", analytics_score, "supported_date")
        if not has_supported_date:
            return _clarification(user_role, "analytics", analytics_score, "date")
        return _decision(
            user_role,
            "analytics",
            analytics_score,
            required_tools=TASK_TOOL_PLANS["analytics"],
        )

    if configuration_intent:
        return _decision(
            user_role,
            "policy_configuration",
            scored_families["policy_configuration"],
            required_tools=TASK_TOOL_PLANS["policy_configuration"],
        )
    if has_knowledge and not campaign_intent:
        return _decision(
            user_role,
            "knowledge",
            scored_families["knowledge"],
            required_tools=TASK_TOOL_PLANS["knowledge"],
        )
    if campaign_intent:
        return _decision(
            user_role,
            "campaign_draft",
            100,
            required_tools=_campaign_plan(text),
        )
    if order_query_intent:
        if not has_one_order:
            return _clarification(user_role, "order_query", 80, "order_id")
        return _decision(
            user_role,
            "order_query",
            80,
            required_tools=TASK_TOOL_PLANS["order_query"],
        )

    has_business_entity = _contains_any(
        text, ("订单", "支付", "退款", "优惠券", "规则", "政策", "campaign", "coupon")
    )
    has_action = _contains_any(text, ("创建", "查", "查询", "退款", "补券", "生成"))
    if text and not has_business_entity and not has_metric and not has_action:
        return _decision(user_role, "small_talk", 100)
    return _clarification(user_role, "unknown", 0, "subject")


class ToolRouter:
    """Expose only the next authorized tool for a retained route decision."""

    def __init__(
        self,
        user_role: str,
        user_message: str = "",
        conversation_context: str = "",
    ):
        self.user_role = user_role
        self.user_message = user_message
        self.conversation_context = conversation_context
        self.decision = classify_request(user_role, user_message)
        self.task_type = self.decision.task_type
        log.debug("tool_router_init", role=user_role, task_type=self.task_type)

    @classmethod
    def from_state(cls, state: Mapping[str, object]) -> "ToolRouter":
        router = cls.__new__(cls)
        router.user_role = str(state.get("user_role", ""))
        router.user_message = ""
        router.conversation_context = ""
        router.decision = RouteDecision.from_state(state)
        router.task_type = router.decision.task_type
        return router

    def route(self, all_tools: list[dict]) -> list[dict]:
        by_name = {tool["name"]: tool for tool in all_tools}
        if self.decision.route_mode == "clarification":
            return []
        if self.decision.route_mode == "controlled":
            name = self.decision.next_tool
            if (
                name is None
                or name not in self.decision.authorized_tools
                or not is_tool_allowed_for_role(name, self.user_role)
            ):
                return []
            return [by_name[name]] if name in by_name else []
        return [
            by_name[name]
            for name in GENERAL_READ_ONLY_TOOLS
            if name in by_name and is_tool_allowed_for_role(name, self.user_role)
        ]
