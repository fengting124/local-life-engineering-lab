"""
pytest 全局配置：把 copilot-agent-service 根目录加入 sys.path，
确保所有测试可以直接 import guardrails / agent / rag / mcp / api 等包。
"""
import sys
import os

# Security-sensitive settings stay required in production. Tests inject an
# explicit non-production value before application modules are imported.
os.environ.setdefault("HITL_PAYLOAD_SIGNING_SECRET", "test-only-hitl-key")
os.environ.setdefault("LANGGRAPH_STRICT_MSGPACK", "true")

# copilot-agent-service/ 根目录
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)
