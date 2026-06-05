#!/bin/bash
# ================================================================
# start-local.sh — WSL 本地开发启动脚本
#
# 功能：
#   1. 检查 WSL 环境
#   2. 加载环境变量
#   3. 启动基础中间件（MySQL / Redis）
#   4. 启动 RAG 服务（Milvus / embedding-service / reranker-service）
#   5. 等待服务就绪
#   6. 打印服务地址和下一步命令
#
# 使用方式：
#   cd infra
#   cp .env.example .env      # 填写 API Key
#   bash scripts/start-local.sh
#   bash scripts/start-local.sh --no-rag   # 不启动 RAG 服务
# ================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="${SCRIPT_DIR}/.."
PROJECT_ROOT="${INFRA_DIR}/.."

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'

info()    { echo -e "${BLUE}ℹ${NC}  $*"; }
success() { echo -e "${GREEN}✅${NC} $*"; }
warn()    { echo -e "${YELLOW}⚠️${NC}  $*"; }
error()   { echo -e "${RED}❌${NC} $*"; exit 1; }

START_RAG=true
for arg in "$@"; do
    [[ "$arg" == "--no-rag" ]] && START_RAG=false
done

echo ""
echo -e "${BOLD}================================================${NC}"
echo -e "${BOLD}   LocalLife × Copilot 本地启动（WSL）${NC}"
echo -e "${BOLD}================================================${NC}"
echo ""

# ── 环境检查 ──
info "运行 WSL 环境检查..."
bash "${SCRIPT_DIR}/check-wsl.sh" || error "WSL 环境检查失败，请按提示修复"

# ── 加载 .env ──
ENV_FILE="${INFRA_DIR}/.env"
if [[ ! -f "$ENV_FILE" ]]; then
    warn ".env 不存在，从模板创建..."
    cp "${INFRA_DIR}/.env.example" "$ENV_FILE"
    warn "请编辑 infra/.env，填写 ANTHROPIC_API_KEY 或 DEEPSEEK_API_KEY 后重新运行"
    exit 1
fi
set -a; source "$ENV_FILE"; set +a
success "环境变量已加载"

cd "$INFRA_DIR"

# ── 启动基础中间件 ──
info "启动 MySQL + Redis..."
docker compose -f docker-compose.dev.yml up -d mysql redis
success "MySQL + Redis 启动中..."

# ── 等待 MySQL ──
info "等待 MySQL 就绪（最多 60s）..."
MAX_WAIT=60; WAITED=0
MYSQL_PWD="${MYSQL_ROOT_PASSWORD:-123456}"
until docker exec local-life-mysql mysqladmin ping -uroot -p"$MYSQL_PWD" --silent 2>/dev/null; do
    [[ $WAITED -ge $MAX_WAIT ]] && error "MySQL 未在 ${MAX_WAIT}s 内就绪"
    printf "."; sleep 2; WAITED=$((WAITED+2))
done
echo ""; success "MySQL 已就绪"

# ── 启动 RAG 服务（可选）──
if [[ "$START_RAG" == "true" ]]; then
    info "启动 RAG 服务（Milvus + embedding-service + reranker-service）..."
    info "首次启动会下载模型（约 2GB），请耐心等待..."
    docker compose -f docker-compose.dev.yml --profile rag up -d --build
    success "RAG 服务启动中（模型加载约需 1-3 分钟）"
else
    warn "跳过 RAG 服务（--no-rag）"
fi

# ── 输出地址 ──
echo ""
echo -e "${BOLD}================================================${NC}"
echo -e "${GREEN}${BOLD}   Docker 服务已启动${NC}"
echo -e "${BOLD}================================================${NC}"
echo ""
echo -e "  ${BOLD}基础中间件：${NC}"
echo -e "    MySQL     ${BLUE}localhost:${MYSQL_PORT:-3306}${NC}"
echo -e "    Redis     ${BLUE}localhost:${REDIS_PORT:-6379}${NC}"
echo ""
if [[ "$START_RAG" == "true" ]]; then
    echo -e "  ${BOLD}RAG 服务（启动后约 1-3 分钟可用）：${NC}"
    echo -e "    Embedding  ${BLUE}http://localhost:${EMBEDDING_PORT:-8100}/health${NC}"
    echo -e "    Reranker   ${BLUE}http://localhost:${RERANKER_PORT:-8101}/health${NC}"
    echo -e "    Milvus     ${BLUE}http://localhost:${MILVUS_PORT:-19530}${NC}"
    echo -e "    Attu UI    ${BLUE}http://localhost:${ATTU_PORT:-3001}${NC}"
    echo ""
fi
echo -e "  ${BOLD}下一步：手动启动应用服务${NC}"
echo ""
echo -e "  ${YELLOW}# 终端 1：Java 主服务${NC}"
echo -e "    cd ${PROJECT_ROOT}/local-life-server"
echo -e "    JAVA_OPTS='-DMYSQL_HOST=localhost -Dspring.data.redis.host=localhost' mvn spring-boot:run"
echo ""
echo -e "  ${YELLOW}# 终端 2：MCP Server${NC}"
echo -e "    cd ${PROJECT_ROOT}/local-life-copilot"
echo -e "    mvn spring-boot:run"
echo ""
echo -e "  ${YELLOW}# 终端 3：Agent Service${NC}"
echo -e "    cd ${PROJECT_ROOT}/copilot-agent-service"
echo -e "    python3 -m venv .venv && source .venv/bin/activate"
echo -e "    pip install -r requirements.txt"
echo -e "    cp .env.example .env   # 填写 LLM API Key"
echo -e "    uvicorn main:app --host 0.0.0.0 --port 8000 --reload"
echo ""
echo -e "  ${YELLOW}# 验证 RAG 服务（等模型加载完再执行）：${NC}"
echo -e "    bash infra/scripts/check-rag.sh"
echo ""
