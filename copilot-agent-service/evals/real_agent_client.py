"""
EvalRunner 的真实 Agent 客户端（HTTP SSE Consumer）。

替换 metrics.py 中的 _mock_invoke，让 Evals 跑真实的 Agent。

工作流程：
  1. EvalRunner 调用 invoke_real_agent(message, role, merchant_id)
  2. 此函数发起 HTTP POST /chat 请求（SSE 流）
  3. 消费 SSE 事件流，收集：
     - tool_call 事件 → 累计工具调用名
     - final_answer 事件 → 最终回答和终止原因
     - error 事件 → 错误信息
  4. 返回 {"tools_called": [...], "final_answer": "...", "tokens": N}

设计要点：
  - 不解析中间 stream 事件（节省时间）
  - 设置超时（每个用例 60 秒），防止 LLM 卡住整个评测
  - 错误用例独立计数，不影响其他用例
"""
import asyncio
import json
import structlog
import httpx

from config.settings import settings

log = structlog.get_logger(__name__)

# 单个用例评测超时（秒）
EVAL_CASE_TIMEOUT = 60


async def invoke_real_agent(
    message: str,
    role: str,
    merchant_id: int | None,
    agent_url: str | None = None,
) -> dict:
    """
    调用真实的 Copilot Agent Service，消费 SSE 流，返回结构化评测结果。

    :param message:     用户输入
    :param role:        用户角色（merchant / cs / admin）
    :param merchant_id: 商家 ID（merchant 角色必填）
    :param agent_url:   Agent 服务地址（默认本地 8000）
    :return: dict 包含：
        tools_called   list[str]    Agent 调用过的工具名（按顺序）
        final_answer   str          最终回答文本
        tokens         int          估算的 token 消耗（如果 SSE 没有给出，返回 0）
        stop_reason    str          终止原因（completed / max_steps / ...）
        error          str | None   错误信息（无错误时为 None）
    """
    base_url = agent_url or f"http://localhost:{settings.app_port}"

    # 评测用 user_id：固定 9999（区分于真实用户）
    headers = {
        "X-User-Id":    "9999",
        "X-User-Role":  role,
        "Accept":       "text/event-stream",
        "Content-Type": "application/json",
    }
    if merchant_id is not None:
        headers["X-Merchant-Id"] = str(merchant_id)

    payload = {
        "message":    message,
        "session_id": 0,   # 让 Agent 自动创建会话
    }

    tools_called: list[str] = []
    final_answer: str = ""
    stop_reason:  str = "unknown"
    error_msg:    str | None = None

    try:
        async with httpx.AsyncClient(timeout=EVAL_CASE_TIMEOUT) as client:
            async with client.stream(
                "POST", f"{base_url}/chat",
                json=payload, headers=headers,
            ) as response:
                if response.status_code != 200:
                    error_msg = f"HTTP {response.status_code}"
                    text = await response.aread()
                    log.warning("eval_agent_http_error",
                                status=response.status_code, body=text[:200])
                    return {
                        "tools_called": [],
                        "final_answer": "",
                        "tokens":       0,
                        "stop_reason":  "http_error",
                        "error":        error_msg,
                    }

                # 解析 SSE 事件流
                current_event: str | None = None
                async for line in response.aiter_lines():
                    if not line:
                        continue
                    if line.startswith("event:"):
                        current_event = line[6:].strip()
                    elif line.startswith("data:"):
                        data_str = line[5:].strip()
                        try:
                            data = json.loads(data_str)
                        except json.JSONDecodeError:
                            continue

                        # 收集工具调用
                        if current_event == "tool_call":
                            tool_name = data.get("tool", "unknown")
                            tools_called.append(tool_name)
                            log.debug("eval_tool_call", tool=tool_name)

                        # 收集最终回答
                        elif current_event == "final_answer":
                            final_answer = data.get("content", "")
                            stop_reason  = data.get("stop_reason", "completed")

                        # HITL 挂起 → 评测用例终止
                        elif current_event == "hitl_request":
                            stop_reason = "pending_approval"
                            # HITL 也算「正确识别需要审批」，记录最后的工具调用
                            break

                        # 错误事件
                        elif current_event == "error":
                            error_msg = data.get("message", "unknown error")
                            stop_reason = "error"
                            break

    except asyncio.TimeoutError:
        error_msg = f"timeout after {EVAL_CASE_TIMEOUT}s"
        stop_reason = "timeout"
    except httpx.RequestError as e:
        error_msg = f"connection error: {e}"
        stop_reason = "connection_error"
    except Exception as e:
        error_msg = f"unexpected: {e}"
        stop_reason = "error"

    log.info(
        "eval_case_done",
        tools=tools_called,
        stop_reason=stop_reason,
        answer_preview=final_answer[:80] if final_answer else "",
    )

    return {
        "tools_called": tools_called,
        "final_answer": final_answer,
        "tokens":       0,   # token 数从 stream 事件中提取需额外解析，当前简化为 0
        "stop_reason":  stop_reason,
        "error":        error_msg,
    }
