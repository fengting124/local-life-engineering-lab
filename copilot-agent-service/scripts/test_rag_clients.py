"""
RAG 客户端连通性测试脚本。

验证 Agent Service 能否通过 HTTP 调用 embedding-service 和 reranker-service。
不依赖 LLM，仅测试 RAG 模型服务链路。

运行：
  cd copilot-agent-service
  .venv/bin/python scripts/test_rag_clients.py

退出码：0=全部通过，非0=有失败项。
"""
import sys
import os

# 确保能 import rag 包
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import httpx

from rag.embedding_client import embed_query, embed_document
from rag.reranker_client import rerank
from rag.config import rag_config


def main() -> int:
    failures = []

    print("=" * 60)
    print("RAG 客户端连通性测试")
    print("=" * 60)
    print(f"EMBEDDING_SERVICE_URL = {rag_config.embedding_service_url}")
    print(f"RERANKER_SERVICE_URL  = {rag_config.reranker_service_url}")
    print(f"EMBEDDING_MODEL_NAME  = {rag_config.embedding_model_name}")
    print(f"EMBEDDING_DIMENSION   = {rag_config.embedding_dimension}")
    print()

    # 1. Embedding 客户端
    print("[1] 测试 embedding-service ...")
    try:
        vec = embed_query("我今天卖了多少钱？")
        if not vec or len(vec) == 0:
            failures.append("embedding 返回空向量")
            print("  ❌ 返回空向量")
        elif all(v == 0.0 for v in vec):
            failures.append("embedding 返回全零向量（服务不可达，触发降级）")
            print("  ❌ 全零向量 —— 服务不可达")
        else:
            print(f"  ✅ 维度={len(vec)} 前3维={[round(x,4) for x in vec[:3]]}")
            if len(vec) != rag_config.embedding_dimension:
                failures.append(f"维度不符：实际{len(vec)} vs 配置{rag_config.embedding_dimension}")
                print(f"  ⚠ 维度与配置不符")
    except Exception as e:
        failures.append(f"embedding 调用异常: {e}")
        print(f"  ❌ 异常: {e}")

    # 2. Reranker 客户端（直接打 reranker-service，验证连通性和打分，不卡 RAG_MIN_SCORE 阈值）
    print("\n[2] 测试 reranker-service ...")
    try:
        payload = {
            "query": "我今天卖了多少钱？",
            "documents": [
                {"id": "1", "text": "商家可以通过销售统计工具查询今日销售额"},
                {"id": "2", "text": "优惠券可以创建和核销"},
            ],
            "top_k": 2,
        }
        with httpx.Client(trust_env=False, timeout=rag_config.timeout_seconds) as client:
            resp = client.post(f"{rag_config.reranker_service_url}/rerank", json=payload)
        resp.raise_for_status()
        data = resp.json()
        results = data.get("results", [])
        if not results:
            failures.append("reranker 返回空结果")
            print("  ❌ 返回空结果")
        else:
            print(f"  ✅ model={data.get('model')} latency={data.get('latency_ms')}ms 结果数={len(results)}")
            for r in results:
                print(f"     id={r['id']} score={r['score']:.4f} text={r['text'][:25]}")
            if results[0]["id"] != "1":
                print(f"  ⚠ 语义最相关文档(id=1)未排首位，实际首位 id={results[0]['id']}")
    except Exception as e:
        failures.append(f"reranker 调用异常: {e}")
        print(f"  ❌ 异常: {e}")

    # 汇总
    print("\n" + "=" * 60)
    if failures:
        print(f"❌ 失败 {len(failures)} 项：")
        for f in failures:
            print(f"   - {f}")
        return 1
    print("✅ RAG 客户端全部连通：embedding + reranker 均正常")
    return 0


if __name__ == "__main__":
    sys.exit(main())
