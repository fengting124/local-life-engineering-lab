"""
LLM-as-Judge 评测模块。

为什么需要 LLM-as-Judge（来自面试高频追问）：
  代码规则评测（工具序列匹配 + 关键词覆盖）只能判断「调对了工具」和「提到了关键词」，
  无法判断「回答是否基于工具返回的事实」「回答是否偷偷编造了内容」。

  LLM-as-Judge 用一个独立 LLM（用轻量模型降低成本）从语义上评判回答质量：
  1. Faithfulness（忠实度）：Agent 的陈述是否都能从工具返回结果中找到依据？
  2. Answer Relevance（相关性）：回答是否切实回答了用户的问题？
  3. Hallucination Detected（幻觉检测）：是否有无法从工具结果推断出的事实声明？

使用轻量模型（claude-haiku）评测的原因：
  - 评测成本：Haiku 约 $0.0003/次，比 Sonnet 便宜 10 倍
  - 评测不需要 Sonnet 的推理深度
  - 但也有风险：轻量模型自身的判断偏差（LLM judge 的局限性）

防止自评偏差（LLM judge 的核心问题）：
  - 使用不同模型评测（业务跑 Claude Sonnet，评测用 Claude Haiku）
  - 固定评测 prompt 和温度（temperature=0）确保可重复性
  - 人工抽检 10% 的评测结果做校准

面试说法：「我们用 LLM-as-Judge 三维度评测 Agent 回答质量，
  用 Haiku 评测而不是 Sonnet 是成本优化；固定 prompt 和温度保证可重复性；
  人工抽检 10% 校准 LLM judge 的偏差。」
"""
import asyncio
import json
import structlog
from dataclasses import dataclass
from typing import Optional

log = structlog.get_logger(__name__)

# 评测使用轻量模型（降低评测成本）
JUDGE_MODEL = "claude-haiku-4-5-20251001"

# 固定温度保证评测可重复性（temperature=0 = 确定性输出）
JUDGE_TEMPERATURE = 0.0


@dataclass
class JudgeResult:
    """LLM-as-Judge 单次评测结果。"""
    faithfulness_score: float     # 0.0~1.0：回答与工具结果的一致性
    relevance_score:    float     # 0.0~1.0：回答与问题的相关性
    hallucination_detected: bool  # True = 发现幻觉（声明无法从证据推断）
    reasoning:          str       # 评判理由（用于人工核查）
    judge_model:        str       # 使用的评测模型（记录版本，防止模型升级导致指标漂移）


# =========================================================
# 评测 Prompts
# =========================================================

_FAITHFULNESS_PROMPT = """你是一个 AI 回答质量评测专家。请评估以下 AI Agent 的回答是否忠实于工具返回的证据。

## 用户问题
{question}

## 工具返回的原始数据（事实依据）
{tool_results}

## Agent 的最终回答
{agent_answer}

## 评测任务
请判断 Agent 的回答中，每一个关键陈述是否都能从「工具返回的原始数据」中找到依据。

评分标准（0.0~1.0）：
- 1.0：所有陈述都有工具数据支撑，没有超出数据范围的推断
- 0.7~0.9：大部分陈述有依据，有少量合理推断但不是关键信息
- 0.4~0.6：部分关键陈述无法从工具数据中找到依据
- 0.0~0.3：回答大量依赖无据推断或编造内容

同时判断是否存在幻觉：如果 Agent 声明了一个具体事实（如具体数字、状态、时间）
但工具返回数据中没有这个信息，则认为存在幻觉。

请按如下 JSON 格式输出（只输出 JSON，不要其他文字）：
{{
  "faithfulness_score": 0.0~1.0的小数,
  "hallucination_detected": true或false,
  "reasoning": "简要说明评判理由（1-2句话）"
}}"""

_RELEVANCE_PROMPT = """你是一个 AI 回答质量评测专家。请评估以下 AI Agent 的回答是否切实回答了用户的问题。

## 用户问题
{question}

## Agent 的最终回答
{agent_answer}

## 评测任务
评分标准（0.0~1.0）：
- 1.0：完整、直接地回答了用户问题，无多余信息
- 0.7~0.9：基本回答了问题，但有轻微偏题或遗漏次要信息
- 0.4~0.6：部分回答了问题，但有明显的偏题或关键信息缺失
- 0.0~0.3：没有回答问题，或回答与问题无关

请按如下 JSON 格式输出（只输出 JSON，不要其他文字）：
{{
  "relevance_score": 0.0~1.0的小数,
  "reasoning": "简要说明评判理由（1-2句话）"
}}"""


# =========================================================
# 评测函数
# =========================================================

async def judge_response(
    question: str,
    agent_answer: str,
    tool_results: list[str],
    api_key: str,
) -> JudgeResult:
    """
    用 LLM 评测 Agent 回答的忠实度、相关性和幻觉情况。

    :param question:     用户原始问题
    :param agent_answer: Agent 最终回答文本
    :param tool_results: 工具调用的原始返回列表（字符串格式）
    :param api_key:      Anthropic API Key
    :return: JudgeResult（含评分和判断原因）
    """
    if not agent_answer or not agent_answer.strip():
        return JudgeResult(
            faithfulness_score=0.0,
            relevance_score=0.0,
            hallucination_detected=False,
            reasoning="Agent 无回答",
            judge_model=JUDGE_MODEL,
        )

    tool_results_text = "\n---\n".join(tool_results) if tool_results else "（无工具调用）"

    try:
        import anthropic
        client = anthropic.AsyncAnthropic(api_key=api_key)

        # 并行评测忠实度和相关性（节省时间）
        faithfulness_task = _call_judge(
            client,
            _FAITHFULNESS_PROMPT.format(
                question=question,
                tool_results=tool_results_text[:3000],   # 截断防止超出上下文
                agent_answer=agent_answer[:2000],
            ),
        )
        relevance_task = _call_judge(
            client,
            _RELEVANCE_PROMPT.format(
                question=question,
                agent_answer=agent_answer[:2000],
            ),
        )

        faith_raw, rel_raw = await asyncio.gather(
            faithfulness_task, relevance_task, return_exceptions=True
        )

        # 解析忠实度评测结果
        faithfulness_score = 0.5
        hallucination_detected = False
        faith_reasoning = ""
        if not isinstance(faith_raw, Exception):
            try:
                faith_data = json.loads(faith_raw)
                faithfulness_score = float(faith_data.get("faithfulness_score", 0.5))
                hallucination_detected = bool(faith_data.get("hallucination_detected", False))
                faith_reasoning = faith_data.get("reasoning", "")
            except (json.JSONDecodeError, ValueError):
                log.warning("judge_faithfulness_parse_failed", raw=faith_raw[:200])

        # 解析相关性评测结果
        relevance_score = 0.5
        rel_reasoning = ""
        if not isinstance(rel_raw, Exception):
            try:
                rel_data = json.loads(rel_raw)
                relevance_score = float(rel_data.get("relevance_score", 0.5))
                rel_reasoning = rel_data.get("reasoning", "")
            except (json.JSONDecodeError, ValueError):
                log.warning("judge_relevance_parse_failed", raw=rel_raw[:200])

        reasoning = f"忠实度: {faith_reasoning} | 相关性: {rel_reasoning}"

        log.info(
            "judge_completed",
            faithfulness=faithfulness_score,
            relevance=relevance_score,
            hallucination=hallucination_detected,
        )

        return JudgeResult(
            faithfulness_score=faithfulness_score,
            relevance_score=relevance_score,
            hallucination_detected=hallucination_detected,
            reasoning=reasoning,
            judge_model=JUDGE_MODEL,
        )

    except Exception as e:
        log.error("judge_failed", error=str(e))
        # 评测失败时返回中性分数（不影响整体评测统计）
        return JudgeResult(
            faithfulness_score=0.5,
            relevance_score=0.5,
            hallucination_detected=False,
            reasoning=f"评测失败: {str(e)[:100]}",
            judge_model=JUDGE_MODEL,
        )


async def _call_judge(client, prompt: str) -> str:
    """调用 Judge LLM，返回原始文本输出。"""
    response = await client.messages.create(
        model=JUDGE_MODEL,
        max_tokens=256,
        temperature=JUDGE_TEMPERATURE,
        messages=[{"role": "user", "content": prompt}],
    )
    return response.content[0].text if response.content else "{}"
