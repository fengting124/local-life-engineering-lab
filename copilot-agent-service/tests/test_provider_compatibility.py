import httpx
from langchain_core.messages import AIMessage, AIMessageChunk, ToolMessage
from langchain_openai import ChatOpenAI

from agent.graph import LLM_RETRY_POLICY


TOOL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "query_order",
        "description": "Query one order",
        "parameters": {
            "type": "object",
            "properties": {"order_id": {"type": "string"}},
            "required": ["order_id"],
        },
    },
}


def test_chat_openai_constructor_and_named_tool_binding_are_compatible():
    client = ChatOpenAI(
        model="deepseek-v4-flash",
        api_key="test-only-provider-key",
        base_url="http://127.0.0.1:1/v1",
        max_retries=0,
    )

    bound = client.bind_tools([TOOL_SCHEMA], tool_choice="query_order")

    assert bound.bound is client
    assert bound.kwargs["tools"][0]["function"]["name"] == "query_order"
    assert bound.kwargs["tool_choice"]["function"]["name"] == "query_order"


def test_ai_tool_call_and_tool_message_pairing_are_preserved():
    assistant = AIMessage(
        content="",
        tool_calls=[
            {
                "name": "query_order",
                "args": {"order_id": "FIXTURE-ORDER-001"},
                "id": "call-fixture-1",
                "type": "tool_call",
            }
        ],
        usage_metadata={
            "input_tokens": 10,
            "output_tokens": 4,
            "total_tokens": 14,
        },
    )
    tool = ToolMessage(
        content='{"status":"PAID"}',
        tool_call_id="call-fixture-1",
        name="query_order",
    )

    assert assistant.tool_calls[0]["args"]["order_id"] == "FIXTURE-ORDER-001"
    assert tool.tool_call_id == assistant.tool_calls[0]["id"]
    assert assistant.usage_metadata["total_tokens"] == 14


def test_streaming_chunks_combine_without_losing_content_or_usage():
    first = AIMessageChunk(content="订单")
    second = AIMessageChunk(
        content="已支付",
        usage_metadata={
            "input_tokens": 8,
            "output_tokens": 2,
            "total_tokens": 10,
        },
    )

    combined = first + second

    assert combined.content == "订单已支付"
    assert combined.usage_metadata["total_tokens"] == 10


def test_retry_policy_distinguishes_transport_from_business_errors():
    assert LLM_RETRY_POLICY.retry_on(
        httpx.RemoteProtocolError("stream disconnected")
    )
    assert not LLM_RETRY_POLICY.retry_on(ValueError("invalid business input"))
