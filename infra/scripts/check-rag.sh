#!/bin/bash
# ================================================================
# check-rag.sh — RAG 服务健康检查
# 使用方式：bash infra/scripts/check-rag.sh
# ================================================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'; BOLD='\033[1m'
PASS=0; WARN=0; FAIL=0

pass() { echo -e "${GREEN}✅${NC} $*"; PASS=$((PASS+1)); }
warn() { echo -e "${YELLOW}⚠️${NC}  $*"; WARN=$((WARN+1)); }
fail() { echo -e "${RED}❌${NC} $*"; FAIL=$((FAIL+1)); }

EMBED_URL="${EMBEDDING_SERVICE_URL:-http://localhost:8100}"
RERANK_URL="${RERANKER_SERVICE_URL:-http://localhost:8101}"
MILVUS_URL="http://${MILVUS_HOST:-localhost}:${MILVUS_PORT:-19530}"

echo ""
echo -e "${BOLD}=== RAG 服务检查 ===${NC}"
echo ""
echo "  Embedding: $EMBED_URL"
echo "  Reranker:  $RERANK_URL"
echo "  Milvus:    $MILVUS_URL"
echo ""

# 1. Embedding /health
echo -e "${BOLD}--- Embedding Service ---${NC}"
EMBED_HEALTH=$(curl -sf --max-time 5 "$EMBED_URL/health" 2>/dev/null || echo "")
if [[ -n "$EMBED_HEALTH" ]]; then
    EMBED_STATUS=$(echo "$EMBED_HEALTH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status','?'))" 2>/dev/null || echo "?")
    EMBED_MODEL=$(echo "$EMBED_HEALTH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('model','?'))" 2>/dev/null || echo "?")
    EMBED_DIM=$(echo "$EMBED_HEALTH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('dimension','?'))" 2>/dev/null || echo "?")
    EMBED_DEV=$(echo "$EMBED_HEALTH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('device','?'))" 2>/dev/null || echo "?")
    if [[ "$EMBED_STATUS" == "ok" ]]; then
        pass "Embedding /health OK | model=$EMBED_MODEL dim=$EMBED_DIM device=$EMBED_DEV"
    else
        fail "Embedding /health 返回异常：$EMBED_HEALTH"
    fi
else
    fail "Embedding Service 无响应：$EMBED_URL（服务未启动？）"
fi

# 2. Embedding /embed
EMBED_TEST=$(curl -sf --max-time 15 -X POST "$EMBED_URL/embed" \
    -H "Content-Type: application/json" \
    -d '{"texts":["query: 我今天卖了多少钱？","passage: 商家可以通过销售统计工具查询今日销售额"],"normalize":true}' 2>/dev/null || echo "")
if [[ -n "$EMBED_TEST" ]]; then
    COUNT=$(echo "$EMBED_TEST" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('count','?'))" 2>/dev/null || echo "?")
    LATENCY=$(echo "$EMBED_TEST" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('latency_ms','?'))" 2>/dev/null || echo "?")
    pass "Embedding /embed OK | count=$COUNT latency=${LATENCY}ms"
else
    fail "Embedding /embed 请求失败"
fi

echo ""
echo -e "${BOLD}--- Reranker Service ---${NC}"

# 3. Reranker /health
RERANK_HEALTH=$(curl -sf --max-time 5 "$RERANK_URL/health" 2>/dev/null || echo "")
if [[ -n "$RERANK_HEALTH" ]]; then
    RERANK_STATUS=$(echo "$RERANK_HEALTH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status','?'))" 2>/dev/null || echo "?")
    RERANK_MODEL=$(echo "$RERANK_HEALTH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('model','?'))" 2>/dev/null || echo "?")
    RERANK_DEV=$(echo "$RERANK_HEALTH" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('device','?'))" 2>/dev/null || echo "?")
    if [[ "$RERANK_STATUS" == "ok" ]]; then
        pass "Reranker /health OK | model=$RERANK_MODEL device=$RERANK_DEV"
    else
        fail "Reranker /health 返回异常：$RERANK_HEALTH"
    fi
else
    fail "Reranker Service 无响应：$RERANK_URL（服务未启动？）"
fi

# 4. Reranker /rerank
RERANK_TEST=$(curl -sf --max-time 15 -X POST "$RERANK_URL/rerank" \
    -H "Content-Type: application/json" \
    -d '{"query":"我今天卖了多少钱？","documents":[{"id":"1","text":"商家可以通过销售统计工具查询今日销售额"},{"id":"2","text":"优惠券可以创建和核销"}],"top_k":1}' 2>/dev/null || echo "")
if [[ -n "$RERANK_TEST" ]]; then
    LATENCY=$(echo "$RERANK_TEST" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('latency_ms','?'))" 2>/dev/null || echo "?")
    TOP_SCORE=$(echo "$RERANK_TEST" | python3 -c "import sys,json; d=json.load(sys.stdin); r=d.get('results',[]); print(r[0]['score'] if r else 'N/A')" 2>/dev/null || echo "?")
    pass "Reranker /rerank OK | latency=${LATENCY}ms top_score=$TOP_SCORE"
else
    fail "Reranker /rerank 请求失败"
fi

echo ""
echo -e "${BOLD}--- Milvus ---${NC}"

# 5. Milvus
MILVUS_HEALTH=$(curl -sf --max-time 5 "$MILVUS_URL/healthz" 2>/dev/null || echo "")
if echo "$MILVUS_HEALTH" | grep -q "OK\|healthy\|alive" 2>/dev/null; then
    pass "Milvus /healthz OK"
else
    # Milvus v2.4 healthz 返回格式不同，尝试 gRPC 端口
    if curl -sf --max-time 3 "http://${MILVUS_HOST:-localhost}:9091/healthz" -o /dev/null 2>/dev/null; then
        pass "Milvus 监控端口 9091 可达"
    else
        warn "Milvus 不可达（可能未启动 rag profile）：docker compose --profile rag up -d"
    fi
fi

# 6. Agent Service RAG 配置
echo ""
echo -e "${BOLD}--- Agent Service RAG 配置 ---${NC}"
AGENT_HEALTH=$(curl -sf --max-time 5 "http://localhost:8000/health" 2>/dev/null || echo "")
if [[ -n "$AGENT_HEALTH" ]]; then
    pass "Agent Service /health OK"
else
    warn "Agent Service 未启动（可手动验证：cd copilot-agent-service && uvicorn main:app --port 8000）"
fi

echo ""
echo -e "${BOLD}=== 检查结果：${GREEN}通过 $PASS${NC}  ${YELLOW}警告 $WARN${NC}  ${RED}失败 $FAIL${NC} ===${NC}"
echo ""
if [[ $FAIL -gt 0 ]]; then
    echo -e "${RED}存在失败项，请先启动 RAG 服务：${NC}"
    echo "  cd infra && docker compose -f docker-compose.dev.yml --profile rag up -d"
    exit 1
fi
