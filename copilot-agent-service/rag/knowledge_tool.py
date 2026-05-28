"""
知识检索工具：Python 侧直接调用 RAG，不经过 Java MCP Server。

架构决策：
  Java MCP Server 是「业务数据」工具的适合之地（订单/支付/门店查询）。
  RAG 检索（Milvus + embedding）是 Python 生态的强项，且需要访问本地模型。
  将 knowledge_search 放在 Python 侧直接实现，避免跨语言传递向量数据。

  在 Agent nodes.py 中，将此工具作为 native Python tool 注册到 LLM，
  而不是通过 MCP Client 调用 Java。

  LangChain tool 定义格式：
    @tool
    async def knowledge_search(query: str) -> str: ...

  Agent 调用时：tool_node 直接调用此函数（无 MCP 网络开销）。

使用场景：
  - 商家询问平台规则（退款规则、活动政策）
  - 客服询问 FAQ（常见订单问题、支付异常）
  - 故障排查（MQ 异常案例、支付回调异常案例）
"""
import json
import structlog
from langchain_core.tools import tool

log = structlog.get_logger(__name__)


def make_knowledge_search_tool(merchant_id: int | None):
    """
    创建 knowledge_search LangChain Tool（绑定当前用户的 merchant_id 权限）。

    为什么要 make_tool 而不是全局 @tool？
    因为每次 Agent 对话的 merchant_id 不同，需要用闭包绑定权限上下文。

    :param merchant_id: 商家 ID（控制 RAG 权限，merchant_private 文档隔离）
    :return: LangChain BaseTool
    """

    @tool
    async def knowledge_search(query: str) -> str:
        """
        在平台知识库中搜索相关文档（规则、FAQ、商家手册、故障案例）。

        适用于：用户询问平台规则、活动政策、退款规则、操作指南等知识性问题。
        不适用于：实时业务数据查询（订单、支付、GMV 等用其他工具）。

        如果搜索结果相关性低于阈值，工具会返回「无相关文档」，
        此时应告知用户无法回答，不要编造内容。

        :param query: 用户问题或关键词（中英文均可）
        :return: 相关文档内容（格式化的上下文文本）
        """
        from rag.pipeline import retrieve
        log.info("knowledge_search_called", query=query[:50], merchant_id=merchant_id)

        result = await retrieve(query=query, merchant_id=merchant_id, top_k=5)

        if result.refused:
            return json.dumps({
                "found":    False,
                "message":  "未找到相关文档，无法基于知识库回答此问题。请建议用户联系人工客服。",
                "sources":  [],
            }, ensure_ascii=False)

        return json.dumps({
            "found":        True,
            "context":      result.context_text,
            "sources":      result.to_dict()["sources"],
            "doc_count":    result.to_dict()["doc_count"],
        }, ensure_ascii=False)

    return knowledge_search
