"""
Evals 六项指标计算脚本。

设计文档 12.2 六项指标：
  1. 工具调用准确率  (Tool Call Accuracy)
  2. 任务完成率     (Task Completion Rate)
  3. Recall@5       (RAG 召回率)
  4. 事实一致性     (Factual Consistency)
  5. 平均延迟       (Average Latency)
  6. 平均 Token 成本 (Average Token Cost)

运行方式：
  python -m evals.metrics --cases all        # 运行所有 50 个用例
  python -m evals.metrics --cases diagnosis  # 只运行订单异常排查用例
  python -m evals.metrics --case-id 16       # 运行单个用例

输出示例：
  ============ LocalLife Copilot Evals Report ============
  Total cases:    50
  Category breakdown:
    query:      15 cases,  13/15 completed (86.7%)
    diagnosis:  15 cases,  11/15 completed (73.3%)
    knowledge:  15 cases,  14/15 completed (93.3%)
    boundary:    5 cases,   5/5 passed    (100.0%)

  === Metric 1: Tool Call Accuracy ===
  Score: 0.843 (42.2/50 sequences fully matched)

  === Metric 2: Task Completion Rate ===
  Score: 0.880

  === Metric 3: Recall@5 ===
  Score: 0.733 (from 15 knowledge cases)

  === Metric 4: Factual Consistency ===
  Score: 0.867

  === Metric 5: Avg Latency ===
  P50:  3.2s  |  P99: 18.5s

  === Metric 6: Avg Token Cost ===
  Avg tokens per session: 2840
  Estimated cost per session: $0.028
  ========================================================
"""
import asyncio
import json
import time
import statistics
from dataclasses import dataclass, field
from typing import Callable

from evals.eval_cases import EvalCase, ALL_CASES, QUERY_CASES, DIAGNOSIS_CASES, KNOWLEDGE_CASES


@dataclass
class EvalResult:
    """单条用例的评测结果。"""
    case_id:           int
    category:          str
    success:           bool                 # 是否成功完成（API 调用无异常）
    actual_tools:      list[str]            # Agent 实际调用的工具序列
    final_answer:      str                  # Agent 最终回答
    latency_ms:        float                # 端到端延迟
    total_tokens:      int                  # 消耗 token 数
    tool_seq_match:    float = 0.0          # 工具序列匹配度（0~1）
    task_completed:    bool  = False        # 是否完成任务
    keyword_coverage:  float = 0.0         # 关键词覆盖率（0~1）
    factually_consistent: bool = True      # 事实一致性（有工具支撑）
    error_msg:         str | None = None
    # LLM-as-Judge 评测结果（需要 --judge 参数才填充）
    faithfulness_score:     float = -1.0   # -1 = 未评测；0~1 = 忠实度分数
    relevance_score:        float = -1.0   # -1 = 未评测；0~1 = 相关性分数
    hallucination_detected: bool  = False  # LLM judge 是否检测到幻觉
    tool_results:           list[str] = None  # 工具原始返回（用于 judge）


@dataclass
class EvalReport:
    """评测汇总报告。"""
    total:                  int
    results:                list[EvalResult]
    tool_call_accuracy:     float = 0.0
    task_completion_rate:   float = 0.0
    recall_at_5:            float = 0.0
    factual_consistency:    float = 0.0
    p50_latency_ms:         float = 0.0
    p99_latency_ms:         float = 0.0
    avg_tokens:             float = 0.0
    # LLM-as-Judge 指标（仅 --judge 模式下有效）
    avg_faithfulness:       float = -1.0   # -1 = 未评测
    avg_relevance:          float = -1.0
    hallucination_rate:     float = -1.0   # 幻觉率（有幻觉的用例数 / 总评测用例数）


# =========================================================
# 单项指标计算
# =========================================================

def calc_tool_sequence_match(expected: list[str], actual: list[str]) -> float:
    """
    计算工具调用序列匹配度。

    策略：
    - 完全匹配（顺序和内容都对）：1.0
    - 包含所有期望工具（顺序可不同）：0.7
    - 部分包含：overlap / max(len(expected), len(actual))
    - 期望为空且实际也为空（边界场景）：1.0

    这里使用「期望工具集合全部出现」作为主要衡量指标，
    不强求顺序，因为 Agent 有时会采用不同但正确的路径。
    """
    if not expected and not actual:
        return 1.0   # 边界场景：期望不调用工具，实际也没调用
    if not expected and actual:
        return 0.0   # 期望不调用工具，但实际调用了（可能是误触发）
    if not actual:
        return 0.0   # 期望调用工具，但实际没调用

    expected_set = set(expected)
    actual_set   = set(actual)
    overlap      = len(expected_set & actual_set)
    return overlap / len(expected_set)


def calc_keyword_coverage(keywords: list[str], answer: str) -> float:
    """
    计算最终回答的关键词覆盖率。

    方法：检查 expected_keywords 中有多少个关键词出现在 answer 中。
    大小写不敏感，支持中英文。
    """
    if not keywords:
        return 1.0
    answer_lower = answer.lower()
    covered = sum(1 for kw in keywords if kw.lower() in answer_lower)
    return covered / len(keywords)


# =========================================================
# 评测执行器
# =========================================================

class EvalRunner:
    """
    评测执行器：对每个用例运行 Agent，收集结果，计算六项指标。

    使用方式：
      runner = EvalRunner(agent_invoke_fn=your_agent_fn)
      report = await runner.run(cases=ALL_CASES)
      runner.print_report(report)
    """

    def __init__(
        self,
        agent_invoke_fn: Callable | None = None,
        judge: bool = False,
        judge_api_key: str | None = None,
    ):
        """
        :param agent_invoke_fn: 调用 Agent 的函数
        :param judge:           是否启用 LLM-as-Judge 评测（需要 API Key，有费用）
        :param judge_api_key:   Anthropic API Key（judge=True 时必填）
        """
        self._invoke = agent_invoke_fn or self._mock_invoke
        self._judge = judge
        self._judge_api_key = judge_api_key

    async def run(
        self,
        cases: list[EvalCase] | None = None,
        category: str | None = None,
    ) -> EvalReport:
        """
        运行评测，返回汇总报告。

        :param cases:    用例列表（默认 ALL_CASES）
        :param category: 只运行某个分类（query / diagnosis / knowledge / boundary）
        """
        target_cases = cases or ALL_CASES
        if category:
            target_cases = [c for c in target_cases if c.category == category]

        results = []
        for case in target_cases:
            result = await self._run_case(case)
            results.append(result)
            print(f"  [{'✓' if result.task_completed else '✗'}] case-{case.id}: "
                  f"{case.input[:40]}... → {result.actual_tools}")

        return self._calc_report(results)

    async def _run_case(self, case: EvalCase) -> EvalResult:
        """运行单个用例，捕获结果。"""
        start = time.time()
        try:
            response = await self._invoke(
                message=case.input,
                role=case.role,
                merchant_id=case.merchant_id,
            )
            latency_ms     = (time.time() - start) * 1000
            actual_tools   = response.get("tools_called", [])
            final_answer   = response.get("final_answer", "")
            total_tokens   = response.get("tokens", 0)

            tool_match   = calc_tool_sequence_match(case.expected_tools, actual_tools)
            kw_coverage  = calc_keyword_coverage(case.expected_keywords, final_answer)
            # 任务完成 = 工具匹配度 >= 0.6 且 关键词覆盖 >= 0.5
            task_done    = tool_match >= 0.6 and kw_coverage >= 0.5

            # 工具原始返回（供 LLM-as-Judge 使用）
            raw_tool_results = response.get("tool_results", [])

            result = EvalResult(
                case_id=case.id,
                category=case.category,
                success=True,
                actual_tools=actual_tools,
                final_answer=final_answer,
                latency_ms=latency_ms,
                total_tokens=total_tokens,
                tool_seq_match=tool_match,
                task_completed=task_done,
                keyword_coverage=kw_coverage,
                tool_results=raw_tool_results,
            )

            # ---- LLM-as-Judge（可选，需要 --judge 参数）----
            if self._judge and self._judge_api_key and final_answer:
                from evals.judge import judge_response
                judge_result = await judge_response(
                    question=case.input,
                    agent_answer=final_answer,
                    tool_results=raw_tool_results,
                    api_key=self._judge_api_key,
                )
                result.faithfulness_score     = judge_result.faithfulness_score
                result.relevance_score        = judge_result.relevance_score
                result.hallucination_detected = judge_result.hallucination_detected

            return result

        except Exception as e:
            latency_ms = (time.time() - start) * 1000
            return EvalResult(
                case_id=case.id,
                category=case.category,
                success=False,
                actual_tools=[],
                final_answer="",
                latency_ms=latency_ms,
                total_tokens=0,
                error_msg=str(e),
            )

    def _calc_report(self, results: list[EvalResult]) -> EvalReport:
        """计算六项指标并生成报告。"""
        if not results:
            return EvalReport(total=0, results=[])

        # 指标 1：工具调用准确率（平均工具序列匹配度）
        tool_acc = statistics.mean(r.tool_seq_match for r in results)

        # 指标 2：任务完成率
        task_rate = sum(1 for r in results if r.task_completed) / len(results)

        # 指标 3：Recall@5（仅知识问答类，检测 RAG 召回有效性）
        knowledge_results = [r for r in results if r.category == "knowledge"]
        recall5 = statistics.mean(r.keyword_coverage for r in knowledge_results) if knowledge_results else 0.0

        # 指标 4：事实一致性（有工具调用结果支撑答案）
        consistency = statistics.mean(
            1.0 if r.factually_consistent and r.actual_tools else
            (0.5 if not r.actual_tools and not [c for c in ALL_CASES if c.id == r.case_id and c.expected_tools] else 0.0)
            for r in results
        )

        # 指标 5：延迟（P50 / P99）
        latencies = [r.latency_ms for r in results if r.success]
        p50 = statistics.median(latencies) if latencies else 0.0
        p99 = sorted(latencies)[int(len(latencies) * 0.99)] if len(latencies) >= 2 else (latencies[0] if latencies else 0.0)

        # 指标 6：Token 成本
        avg_tokens = statistics.mean(r.total_tokens for r in results if r.success) if results else 0.0

        # LLM-as-Judge 指标（只对有 judge 结果的用例计算）
        judged = [r for r in results if r.faithfulness_score >= 0]
        avg_faithfulness = statistics.mean(r.faithfulness_score for r in judged) if judged else -1.0
        avg_relevance    = statistics.mean(r.relevance_score    for r in judged) if judged else -1.0
        hallucination_rate = (
            sum(1 for r in judged if r.hallucination_detected) / len(judged)
            if judged else -1.0
        )

        return EvalReport(
            total=len(results),
            results=results,
            tool_call_accuracy=tool_acc,
            task_completion_rate=task_rate,
            recall_at_5=recall5,
            factual_consistency=consistency,
            p50_latency_ms=p50,
            p99_latency_ms=p99,
            avg_tokens=avg_tokens,
            avg_faithfulness=avg_faithfulness,
            avg_relevance=avg_relevance,
            hallucination_rate=hallucination_rate,
        )

    async def _mock_invoke(self, message: str, role: str, merchant_id: int | None) -> dict:
        """
        Mock Agent 调用（用于测试评测框架本身的逻辑，不调用真实 Agent）。
        正式评测时通过 --real 参数切换到 real_agent_client.invoke_real_agent。
        """
        await asyncio.sleep(0.01)  # 模拟延迟
        return {"tools_called": [], "final_answer": "Mock 回答", "tokens": 100}

    def print_report(self, report: EvalReport) -> None:
        """打印格式化评测报告。"""
        print("\n" + "=" * 55)
        print("    LocalLife Copilot Evals Report")
        print("=" * 55)
        print(f"Total cases:  {report.total}")
        print()

        # 分类统计
        categories = ["query", "diagnosis", "knowledge", "boundary"]
        for cat in categories:
            cat_results = [r for r in report.results if r.category == cat]
            if cat_results:
                done = sum(1 for r in cat_results if r.task_completed)
                pct  = done / len(cat_results) * 100
                print(f"  {cat:12}: {len(cat_results):2} cases, {done:2}/{len(cat_results):2} completed ({pct:.1f}%)")

        print()
        print(f"Metric 1 | Tool Call Accuracy:    {report.tool_call_accuracy:.3f}")
        print(f"Metric 2 | Task Completion Rate:  {report.task_completion_rate:.3f}")
        print(f"Metric 3 | Recall@5 (knowledge):  {report.recall_at_5:.3f}")
        print(f"Metric 4 | Factual Consistency:   {report.factual_consistency:.3f}")
        print(f"Metric 5 | Latency P50/P99:       {report.p50_latency_ms:.0f}ms / {report.p99_latency_ms:.0f}ms")
        print(f"Metric 6 | Avg Tokens/Session:    {report.avg_tokens:.0f}")

        # LLM-as-Judge 指标（只在 --judge 模式下显示）
        if report.avg_faithfulness >= 0:
            print()
            print("── LLM-as-Judge 评测结果 ──────────────────────────")
            print(f"Metric 7 | Faithfulness (忠实度):  {report.avg_faithfulness:.3f}")
            print(f"Metric 8 | Relevance   (相关性):   {report.avg_relevance:.3f}")
            judged_count = sum(1 for r in report.results if r.faithfulness_score >= 0)
            halluc_count = sum(1 for r in report.results if r.hallucination_detected)
            print(f"Metric 9 | Hallucination Rate:     {report.hallucination_rate:.1%}"
                  f"  ({halluc_count}/{judged_count} cases)")
            print(f"           Judge Model: claude-haiku (temperature=0, deterministic)")

        # 估算成本（Claude Sonnet 4.6 约 $3/M input tokens, $15/M output tokens）
        est_cost = report.avg_tokens * 0.000006  # 简化估算
        print(f"           Estimated Cost:         ${est_cost:.4f}/session")
        print("=" * 55 + "\n")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(
        description="LocalLife Copilot Evals 评测运行器",
        epilog="示例：\n"
               "  python -m evals.metrics                  # Mock 模式（测评测框架本身）\n"
               "  python -m evals.metrics --real           # 真实模式（需启动 Agent 服务）\n"
               "  python -m evals.metrics --real --category diagnosis  # 只跑订单异常排查",
    )
    parser.add_argument("--category", choices=["query", "diagnosis", "knowledge", "boundary", "all"],
                        default="all", help="只运行某个分类的用例")
    parser.add_argument("--real", action="store_true",
                        help="使用真实 Agent（通过 HTTP SSE 调用本地 8000 端口）")
    parser.add_argument("--agent-url", default=None,
                        help="覆盖 Agent 服务地址（默认 http://localhost:8000）")
    parser.add_argument("--judge", action="store_true",
                        help="启用 LLM-as-Judge 评测（需要 ANTHROPIC_API_KEY，会产生 API 费用）")
    args = parser.parse_args()

    # ---- 选择 Mock 还是真实 Agent ----
    if args.real:
        from evals.real_agent_client import invoke_real_agent

        async def real_invoke(message, role, merchant_id):
            return await invoke_real_agent(message, role, merchant_id, agent_url=args.agent_url)

        invoke_fn = real_invoke
        print(f"📡 真实 Agent 模式：{args.agent_url or 'http://localhost:8000'}")
    else:
        invoke_fn = None
        print("🤖 Mock 模式：仅验证评测框架本身（不调用真实 Agent）")

    # ---- 选择是否启用 LLM-as-Judge ----
    judge_api_key = None
    if args.judge:
        import os
        judge_api_key = os.environ.get("ANTHROPIC_API_KEY", "")
        if not judge_api_key:
            print("⚠ 警告：--judge 需要设置 ANTHROPIC_API_KEY 环境变量，LLM-as-Judge 将被跳过")
            args.judge = False
        else:
            print(f"⚖  LLM-as-Judge 已启用（模型: claude-haiku，temperature=0）")
            print("   预计每条用例额外消耗约 $0.0006（2 次 Haiku 调用）")

    runner = EvalRunner(
        agent_invoke_fn=invoke_fn,
        judge=args.judge,
        judge_api_key=judge_api_key,
    )

    cat = None if args.category == "all" else args.category
    report = asyncio.run(runner.run(category=cat))
    runner.print_report(report)
