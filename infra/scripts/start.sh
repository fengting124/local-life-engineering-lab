#!/bin/bash
# ================================================================
# start.sh — LocalLife × Copilot 一键完整启动脚本
#
# 执行顺序：
#   1. 检查必要工具（docker compose, curl, mysql）
#   2. 加载环境变量（infra/.env）
#   3. 启动中间件（MySQL / Redis / ES / RocketMQ）
#   4. 等待中间件就绪
#   5. 初始化数据库（迁移脚本）
#   6. 构建并启动应用服务（三个容器）
#   7. 等待服务健康检查
#   8. 启动 Nginx（可选）
#   9. 打印访问地址
#
# 使用方式：
#   cd infra
#   cp .env.example .env         # 填写 ANTHROPIC_API_KEY
#   bash scripts/start.sh        # 完整启动
#   bash scripts/start.sh --skip-build   # 跳过重新构建（已有镜像时）
# ================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="${SCRIPT_DIR}/.."
PROJECT_ROOT="${INFRA_DIR}/.."

# ── 颜色输出 ──
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'

info()    { echo -e "${BLUE}ℹ${NC}  $*"; }
success() { echo -e "${GREEN}✅${NC} $*"; }
warn()    { echo -e "${YELLOW}⚠️${NC}  $*"; }
error()   { echo -e "${RED}❌${NC} $*"; exit 1; }

SKIP_BUILD=false
for arg in "$@"; do
  [ "$arg" = "--skip-build" ] && SKIP_BUILD=true
done

echo ""
echo -e "${BOLD}================================================${NC}"
echo -e "${BOLD}   LocalLife × Copilot 全栈启动${NC}"
echo -e "${BOLD}================================================${NC}"
echo ""

# ---- Step 0: 检查必要工具 ----
info "检查环境..."
command -v docker   >/dev/null 2>&1 || error "未找到 docker，请先安装 Docker Desktop"
command -v mysql    >/dev/null 2>&1 || warn  "未找到 mysql CLI，数据库初始化将通过 Docker 执行"
docker compose version >/dev/null 2>&1 || error "未找到 docker compose，请升级 Docker Desktop"
success "环境检查通过"

# ---- Step 1: 加载环境变量 ----
ENV_FILE="${INFRA_DIR}/.env"
if [ ! -f "${ENV_FILE}" ]; then
  warn ".env 文件不存在，从模板创建..."
  cp "${INFRA_DIR}/.env.example" "${ENV_FILE}"
  warn "请编辑 infra/.env，填写 ANTHROPIC_API_KEY 后重新运行"
  exit 1
fi

# 检查关键变量
source "${ENV_FILE}" 2>/dev/null || true
if [ -z "${ANTHROPIC_API_KEY}" ] || [ "${ANTHROPIC_API_KEY}" = "sk-ant-xxxxxxxxxxxxxxxxxxxxxx" ]; then
  warn "ANTHROPIC_API_KEY 未设置，Agent 服务将无法使用 LLM（其他功能正常）"
fi

success "环境变量已加载"

# ---- Step 2: 启动中间件 ----
info "启动基础中间件（MySQL / Redis）..."
cd "${INFRA_DIR}"
docker compose -f docker-compose.dev.yml up -d mysql redis
success "MySQL + Redis 启动中..."

# ---- Step 3: 可选中间件 ----
read -t 5 -p "$(echo -e "${YELLOW}是否启动完整中间件（ES / RocketMQ）？[y/N] ${NC}")" -n 1 REPLY 2>/dev/null || REPLY="n"
echo ""
if [[ "${REPLY}" =~ ^[Yy]$ ]]; then
  info "启动 Elasticsearch..."
  docker compose -f docker-compose.dev.yml --profile search up -d

  info "启动 RocketMQ..."
  docker compose -f docker-compose.dev.yml --profile mq up -d

  success "全部中间件已启动"
else
  info "跳过 ES / RocketMQ（可后续通过 --profile search/mq 启动）"
fi

# ---- Step 4: 等待 MySQL 就绪 ----
info "等待 MySQL 就绪..."
MAX_WAIT=60; WAITED=0
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-123456}"
until docker exec local-life-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -e "SELECT 1" >/dev/null 2>&1; do
  [ ${WAITED} -ge ${MAX_WAIT} ] && error "MySQL 未在 ${MAX_WAIT}s 内就绪"
  printf "."; sleep 2; WAITED=$((WAITED + 2))
done
echo ""
success "MySQL 已就绪"

# ---- Step 5: 数据库初始化 ----
info "执行数据库迁移..."
# 通过 Docker 内部执行（不依赖本地 mysql CLI）
docker exec local-life-mysql bash -c "
  mysql -uroot -p${MYSQL_ROOT_PASSWORD} -e 'CREATE DATABASE IF NOT EXISTS local_life CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;' 2>/dev/null
  echo 'Database ready'
" 2>/dev/null || true

# 执行迁移 SQL（使用 docker cp 将文件传入容器执行）
MIGRATION_DIR="${PROJECT_ROOT}/local-life-server/src/main/resources/db/migration"
COPILOT_MIGRATION_DIR="${PROJECT_ROOT}/local-life-copilot/src/main/resources/db/migration"

run_migrations_in_docker() {
  local dir="$1"
  for f in $(ls "${dir}"/V*.sql 2>/dev/null | sort -V); do
    local fname=$(basename "${f}")
    local version=$(echo "${fname}" | sed 's/^\(V[0-9]*\)__.*/\1/')
    # 检查是否已执行
    local already=$(docker exec local-life-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" local_life -sN \
      -e "SELECT COUNT(*) FROM schema_migrations WHERE version='${version}'" 2>/dev/null || echo "0")
    if [ "${already}" = "1" ]; then
      echo "   ⏭  ${fname} (已执行)"
    else
      docker exec -i local-life-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" local_life < "${f}" 2>/dev/null
      docker exec local-life-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" local_life -e \
        "INSERT IGNORE INTO schema_migrations(version, description) VALUES('${version}','${fname}')" 2>/dev/null || true
      echo "   ✅ ${fname}"
    fi
  done
}

# 创建迁移记录表
docker exec local-life-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" local_life -e "
  CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(20) NOT NULL PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
  );" 2>/dev/null || true

run_migrations_in_docker "${MIGRATION_DIR}"
run_migrations_in_docker "${COPILOT_MIGRATION_DIR}"
success "数据库迁移完成"

# ---- Step 6: 构建并启动应用服务 ----
if [ "${SKIP_BUILD}" = "true" ]; then
  info "跳过构建，直接启动应用服务..."
  docker compose -f docker-compose.dev.yml --profile app up -d
else
  info "构建应用镜像（首次约需 3-5 分钟）..."
  docker compose -f docker-compose.dev.yml --profile app up -d --build
fi

# ---- Step 7: 等待服务健康 ----
info "等待应用服务启动..."
services=("locallife-server:8080/actuator/health" "local-life-copilot:8081/actuator/health" "copilot-agent-service:8000/health")
for svc in "${services[@]}"; do
  name="${svc%%:*}"; addr="${svc#*:}"
  printf "   等待 ${name}..."
  MAX=120; W=0
  until curl -sf "http://localhost:${addr}" >/dev/null 2>&1; do
    [ ${W} -ge ${MAX} ] && { echo "超时"; warn "${name} 未能在 ${MAX}s 内就绪"; break; }
    printf "."; sleep 3; W=$((W + 3))
  done
  echo " ✅"
done

# ---- Step 8: 输出访问地址 ----
echo ""
echo -e "${BOLD}================================================${NC}"
echo -e "${GREEN}${BOLD}   🚀 LocalLife Copilot 已成功启动！${NC}"
echo -e "${BOLD}================================================${NC}"
echo ""
echo -e "  📱 Chat UI（商家/客服）  ${BLUE}http://localhost:8000/${NC}"
echo -e "  ⚠️  审批工作台（运营）    ${BLUE}http://localhost:8000/approval${NC}"
echo -e "  📖 API 文档              ${BLUE}http://localhost:8000/docs${NC}"
echo -e "  🏪 LocalLife API         ${BLUE}http://localhost:8080/api/v1/${NC}"
echo ""
echo -e "  📊 Grafana 监控          ${BLUE}http://localhost:3000${NC}  (admin/admin)"
echo ""
echo -e "  ${YELLOW}停止服务：${NC} docker compose -f infra/docker-compose.dev.yml --profile app down"
echo ""
