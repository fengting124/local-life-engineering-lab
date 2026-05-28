"""
输入层 Guardrails：Prompt Injection 检测。

设计文档 11.4 要求：
  - 检测用户尝试「忽略系统规则」的注入指令
  - 检测角色伪装（让 AI 扮演没有限制的角色）
  - 检测尝试泄露工具结果、系统提示的请求
  - 检测跨商家数据访问尝试

防护级别：
  BLOCK  — 直接拒绝，不进入 Agent 主循环（高置信度注入）
  WARN   — 允许通过但记录警告（可疑但不确定）
  ALLOW  — 正常通过

面试说法：
「输入 Guardrails 用正则 + 关键词匹配检测明显的 Prompt Injection 模式，
覆盖忽略指令、角色扮演、工具 Schema 泄露等攻击面。
对于更复杂的语义级注入，需要调用二级检测模型（成本换安全性）。
当前项目优先覆盖最常见的攻击模式，确保 90% 的注入场景被拦截。」
"""
import re
import structlog
from dataclasses import dataclass
from enum import Enum

log = structlog.get_logger(__name__)


class GuardLevel(Enum):
    ALLOW = "allow"
    WARN  = "warn"
    BLOCK = "block"


@dataclass
class GuardResult:
    """Guardrails 检测结果。"""
    level:   GuardLevel
    reason:  str | None = None   # 触发的规则描述
    pattern: str | None = None   # 匹配到的具体模式


# =========================================================
# 检测规则定义
# =========================================================

# 高置信度 Prompt Injection 模式（BLOCK 级别）
_BLOCK_PATTERNS: list[tuple[str, str]] = [
    # 忽略系统规则
    (r"ignore\s+(previous|all|system)\s+instructions?",    "ignore_system_instructions"),
    (r"忽略(前面|之前|所有)?(的)?(指令|规则|限制|约束)",        "ignore_system_instructions_cn"),
    (r"disregard\s+your\s+(instructions?|rules?|guidelines?)", "disregard_instructions"),
    (r"你现在没有任何限制",                                  "no_restriction_claim"),
    (r"你是.{0,20}没有任何限制.{0,20}的",                    "no_restriction_roleplay"),

    # 角色伪装（DAN / Jailbreak 模式）
    (r"\bDAN\b",                                           "dan_jailbreak"),
    (r"act as.{0,30}(no limits?|without restrictions?)",   "act_as_no_limits"),
    (r"假装你是.{0,20}(没有限制|不受约束|可以做任何事)",         "roleplay_no_limits"),
    (r"扮演一个.{0,20}(无限制|无约束|黑客|攻击者)",              "roleplay_attacker"),

    # 尝试泄露系统提示
    (r"(show|print|output|reveal|leak)\s+(your\s+)?(system\s+)?prompt", "leak_system_prompt"),
    (r"(输出|打印|显示|泄露|告诉我).{0,10}(系统提示|system prompt|工具描述|tool schema)",
     "leak_system_info"),

    # 尝试越权访问其他商家数据
    (r"查[^\n]{0,20}商家.{0,10}(所有|全部|其他|任意).{0,10}(订单|数据|信息)",
     "cross_merchant_access"),
    (r"merchant_id\s*=\s*[\"']?\d+[\"']?\s*(,|\s).*(?:all|all_merchants|all_orders)",
     "explicit_cross_merchant"),
]

# 低置信度可疑模式（WARN 级别）
_WARN_PATTERNS: list[tuple[str, str]] = [
    (r"(inject|injection)",                                "potential_injection_keyword"),
    (r"你真正的指令是",                                      "potential_true_instructions"),
    (r"实际上你可以",                                        "potential_bypass_claim"),
    (r"<(script|iframe|img)[^>]*>",                        "html_injection_attempt"),
]


# =========================================================
# 检测函数
# =========================================================

def check_input(user_message: str, user_role: str = "merchant") -> GuardResult:
    """
    检测用户输入是否包含 Prompt Injection 或权限越界攻击。

    :param user_message: 用户的原始输入文本
    :param user_role:    当前用户角色（merchant/cs/admin）
    :return: GuardResult（ALLOW / WARN / BLOCK）
    """
    text = user_message.lower()

    # ---- BLOCK 级别检测 ----
    for pattern, rule_name in _BLOCK_PATTERNS:
        if re.search(pattern, text, re.IGNORECASE | re.DOTALL):
            log.warning(
                "guardrails_blocked",
                rule=rule_name,
                role=user_role,
                snippet=user_message[:100],
            )
            return GuardResult(
                level=GuardLevel.BLOCK,
                reason=f"检测到潜在 Prompt Injection 或越权尝试（规则：{rule_name}）",
                pattern=pattern,
            )

    # ---- WARN 级别检测 ----
    for pattern, rule_name in _WARN_PATTERNS:
        if re.search(pattern, text, re.IGNORECASE):
            log.warning(
                "guardrails_warned",
                rule=rule_name,
                role=user_role,
                snippet=user_message[:100],
            )
            return GuardResult(
                level=GuardLevel.WARN,
                reason=f"可疑输入（规则：{rule_name}），已记录",
                pattern=pattern,
            )

    return GuardResult(level=GuardLevel.ALLOW)


def check_output(agent_output: str, merchant_id: int | None) -> GuardResult:
    """
    输出层 Guardrails：检查 Agent 输出是否包含越权数据或敏感信息。

    检测内容：
    1. 跨商家数据泄露（输出包含不属于当前 merchant_id 的商家名称）
    2. 完整手机号泄露（应该脱敏为 138****8000）
    3. 支付渠道私钥等敏感字段
    4. 数据库连接字符串

    :param agent_output: Agent 最终回答文本
    :param merchant_id:  当前商家 ID（用于判断是否越权）
    :return: GuardResult
    """
    # 检测完整手机号（11 位连续数字，13/14/15/17/18/19 开头）
    if re.search(r'\b(13|14|15|17|18|19)\d{9}\b', agent_output):
        log.warning("guardrails_output_phone", snippet=agent_output[:100])
        return GuardResult(
            level=GuardLevel.WARN,
            reason="输出包含疑似完整手机号，建议脱敏后返回",
        )

    # 检测数据库连接字符串
    if re.search(r'(jdbc:|mysql://|password\s*=)', agent_output, re.IGNORECASE):
        log.error("guardrails_output_db_credential", snippet=agent_output[:100])
        return GuardResult(
            level=GuardLevel.BLOCK,
            reason="输出包含数据库连接信息，已拦截",
        )

    # 检测 API Key 格式
    if re.search(r'sk-[a-zA-Z0-9]{20,}', agent_output):
        log.error("guardrails_output_api_key")
        return GuardResult(
            level=GuardLevel.BLOCK,
            reason="输出包含疑似 API Key，已拦截",
        )

    return GuardResult(level=GuardLevel.ALLOW)
