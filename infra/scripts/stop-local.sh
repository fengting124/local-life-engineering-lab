#!/bin/bash
# ================================================================
# stop-local.sh — 停止本地开发服务
#
# 注意：
#   - 不删除 volume（数据保留）
#   - 不删除模型缓存（model-cache volume 保留）
#   - 如需彻底清理：docker compose down -v
# ================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="${SCRIPT_DIR}/.."

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'; BOLD='\033[1m'

echo ""
echo -e "${BOLD}=== 停止 LocalLife 本地服务 ===${NC}"
echo ""

cd "$INFRA_DIR"

echo -e "${YELLOW}停止应用服务容器...${NC}"
docker compose -f docker-compose.dev.yml --profile app down 2>/dev/null || true

echo -e "${YELLOW}停止 RAG 服务容器...${NC}"
docker compose -f docker-compose.dev.yml --profile rag down 2>/dev/null || true

echo -e "${YELLOW}停止基础中间件...${NC}"
docker compose -f docker-compose.dev.yml down 2>/dev/null || true

echo ""
echo -e "${GREEN}✅ 所有容器已停止。Volume 数据已保留（包含模型缓存）。${NC}"
echo ""
echo -e "如需彻底清理所有数据（含模型缓存）："
echo -e "  docker compose -f infra/docker-compose.dev.yml down -v"
echo ""
