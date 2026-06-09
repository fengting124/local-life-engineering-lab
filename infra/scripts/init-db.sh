#!/bin/bash
# ================================================================
# init-db.sh — 数据库初始化脚本（幂等，可重复执行）
#
# 功能：
#   1. 等待 MySQL 就绪
#   2. 创建数据库（若不存在）
#   3. 按版本顺序执行所有 SQL 迁移（V1~V9 + Copilot V1）
#   4. 使用迁移记录表（schema_migrations）防止重复执行
#      迁移 key 使用 namespace:version（如 server:V1 / copilot:V1），避免不同模块
#      都有 V1 时互相遮挡。历史库中已有的 server 侧旧 key（V1~V9）会被兼容跳过。
#
# 使用方式：
#   # 本地开发（MySQL 在 localhost）
#   bash infra/scripts/init-db.sh
#
#   # 指定 MySQL 主机
#   MYSQL_HOST=mysql MYSQL_PORT=3306 bash infra/scripts/init-db.sh
#
#   # Docker 容器内
#   docker exec local-life-mysql bash /scripts/init-db.sh
# ================================================================
set -e

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-123456}"
MYSQL_DATABASE="${MYSQL_DATABASE:-local_life}"

# 迁移文件路径：
# - 直接在宿主机执行时，从项目根目录计算
# - 在 Docker 容器内执行时，使用挂载路径 /migrations/
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -d "/migrations/server" ]; then
  # Docker 容器内：文件挂载在 /migrations/
  SERVER_MIGRATION_DIR="/migrations/server"
  COPILOT_MIGRATION_DIR="/migrations/copilot"
else
  # 宿主机执行：从脚本位置推算项目根目录
  PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
  SERVER_MIGRATION_DIR="${PROJECT_ROOT}/local-life-server/src/main/resources/db/migration"
  COPILOT_MIGRATION_DIR="${PROJECT_ROOT}/local-life-copilot/src/main/resources/db/migration"
fi

# MySQL 命令（带通用参数）
MYSQL_CMD="mysql -h${MYSQL_HOST} -P${MYSQL_PORT} -u${MYSQL_USER} -p${MYSQL_PASSWORD} --protocol=tcp"

echo "================================================"
echo "  LocalLife 数据库初始化"
echo "  Host: ${MYSQL_HOST}:${MYSQL_PORT}"
echo "  Database: ${MYSQL_DATABASE}"
echo "================================================"

# ---- Step 1：等待 MySQL 就绪 ----
echo ""
echo "⏳ 等待 MySQL 就绪..."
MAX_WAIT=60
WAITED=0
until ${MYSQL_CMD} -e "SELECT 1" >/dev/null 2>&1; do
  if [ ${WAITED} -ge ${MAX_WAIT} ]; then
    echo "❌ MySQL ${MAX_WAIT}s 内未就绪，退出"
    exit 1
  fi
  echo "   还未就绪，等待 2s... (${WAITED}/${MAX_WAIT}s)"
  sleep 2
  WAITED=$((WAITED + 2))
done
echo "✅ MySQL 已就绪"

# ---- Step 2：创建数据库 ----
echo ""
echo "📦 创建数据库 ${MYSQL_DATABASE}（若不存在）..."
${MYSQL_CMD} -e "CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "✅ 数据库就绪"

# ---- Step 3：创建迁移记录表 ----
${MYSQL_CMD} ${MYSQL_DATABASE} -e "
CREATE TABLE IF NOT EXISTS \`schema_migrations\` (
  \`version\` VARCHAR(20) NOT NULL COMMENT '迁移版本，如 V1',
  \`description\` VARCHAR(200) NOT NULL COMMENT '迁移描述',
  \`applied_at\` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
  PRIMARY KEY (\`version\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库迁移记录（防止重复执行）';
"

# ---- Step 4：执行迁移 ----
run_migration() {
  local namespace="$1"
  local sql_file="$2"
  local filename=$(basename "${sql_file}")
  # 提取版本号：V1__xxx.sql → V1
  local version=$(echo "${filename}" | sed 's/^\(V[0-9]*\)__.*/\1/')
  local version_key="${namespace}:${version}"
  local description=$(echo "${filename}" | sed 's/^V[0-9]*__\(.*\)\.sql$/\1/')

  # 检查是否已执行
  local already_applied=$(${MYSQL_CMD} ${MYSQL_DATABASE} -sN -e \
    "SELECT COUNT(*) FROM schema_migrations WHERE version='${version_key}'" 2>/dev/null || echo "0")

  if [ "${already_applied}" = "1" ]; then
    echo "   ⏭  ${filename} (${version_key} 已执行，跳过)"
    return 0
  fi

  # 兼容历史库：早期只记录 V1/V2...，这些记录都来自 server 迁移。
  # 发现旧 key 时补写新的 namespaced key，不重跑 SQL，避免 ALTER/INSERT 重复执行。
  if [ "${namespace}" = "server" ]; then
    local legacy_applied=$(${MYSQL_CMD} ${MYSQL_DATABASE} -sN -e \
      "SELECT COUNT(*) FROM schema_migrations WHERE version='${version}'" 2>/dev/null || echo "0")
    if [ "${legacy_applied}" = "1" ]; then
      ${MYSQL_CMD} ${MYSQL_DATABASE} -e \
        "INSERT IGNORE INTO schema_migrations(version, description) VALUES('${version_key}', '${description}');"
      echo "   ⏭  ${filename} (${version} legacy 已执行，补记 ${version_key} 后跳过)"
      return 0
    fi
  fi

  echo "   🔄 执行 ${filename}..."
  if ${MYSQL_CMD} ${MYSQL_DATABASE} < "${sql_file}"; then
    ${MYSQL_CMD} ${MYSQL_DATABASE} -e \
      "INSERT INTO schema_migrations(version, description) VALUES('${version_key}', '${description}');"
    echo "   ✅ ${filename} 完成"
  else
    echo "   ❌ ${filename} 执行失败！"
    exit 1
  fi
}

echo ""
echo "📝 执行 LocalLife Server 迁移..."
# 按版本顺序执行
for f in $(ls "${SERVER_MIGRATION_DIR}"/V*.sql 2>/dev/null | sort -V); do
  run_migration "server" "${f}"
done

echo ""
echo "📝 执行 Copilot 迁移..."
for f in $(ls "${COPILOT_MIGRATION_DIR}"/V*.sql 2>/dev/null | sort -V); do
  run_migration "copilot" "${f}"
done

echo ""
echo "================================================"
echo "✅ 数据库初始化完成！"
echo ""
echo "   已执行的版本："
${MYSQL_CMD} ${MYSQL_DATABASE} -e \
  "SELECT version, description, applied_at FROM schema_migrations ORDER BY version;" 2>/dev/null || true
echo "================================================"
