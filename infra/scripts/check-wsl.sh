#!/bin/bash
# ================================================================
# check-wsl.sh — WSL 环境检查脚本
# 使用方式：bash infra/scripts/check-wsl.sh
# ================================================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'; BOLD='\033[1m'
PASS=0; WARN=0; FAIL=0

pass() { echo -e "${GREEN}✅${NC} $*"; PASS=$((PASS+1)); }
warn() { echo -e "${YELLOW}⚠️${NC}  $*"; WARN=$((WARN+1)); }
fail() { echo -e "${RED}❌${NC} $*"; FAIL=$((FAIL+1)); }

echo ""
echo -e "${BOLD}=== WSL 环境检查 ===${NC}"
echo ""

# 1. 是否在 WSL
if grep -qi "microsoft" /proc/version 2>/dev/null; then
    pass "运行在 WSL 内核"
else
    fail "不在 WSL 环境（/proc/version 无 microsoft 标识）"
fi

# 2. 是否在 /home 路径
CWD=$(pwd)
if echo "$CWD" | grep -q "^/home/"; then
    pass "项目在 /home 路径下：$CWD"
elif echo "$CWD" | grep -q "^/mnt/"; then
    fail "项目在 Windows 挂载路径 $CWD（文件 IO 性能差，请迁移到 /home/\$USER/projects/）"
else
    warn "项目路径：$CWD（推荐放在 /home/\$USER/projects/）"
fi

# 3. 代理检查
echo ""
echo -e "${BOLD}--- 代理配置 ---${NC}"
if [[ -n "${http_proxy:-}" ]] || [[ -n "${HTTP_PROXY:-}" ]]; then
    PROXY="${http_proxy:-${HTTP_PROXY:-}}"
    pass "代理已配置：$PROXY"
    # 测试联通性
    if curl -sI --max-time 8 https://github.com -o /dev/null 2>&1; then
        pass "GitHub 可达"
    else
        warn "GitHub 不可达（代理可能未启动）"
    fi
    if curl -sI --max-time 8 https://huggingface.co -o /dev/null 2>&1; then
        pass "HuggingFace 可达"
    else
        warn "HuggingFace 不可达（模型下载会失败）"
    fi
else
    warn "未检测到代理环境变量（如需访问 HuggingFace/GitHub，请设置 http_proxy）"
fi

# 4. Git
echo ""
echo -e "${BOLD}--- 工具检查 ---${NC}"
if command -v git >/dev/null 2>&1; then
    pass "git: $(git --version)"
else
    fail "git 未安装：sudo apt install -y git"
fi

# 5. Docker
if command -v docker >/dev/null 2>&1; then
    pass "docker: $(docker --version 2>&1 | head -1)"
    if docker compose version >/dev/null 2>&1; then
        pass "docker compose: $(docker compose version)"
    else
        fail "docker compose 不可用（需要 Docker Desktop v2.0+）"
    fi
else
    fail "docker 未找到 → 请在 Docker Desktop: Settings > Resources > WSL Integration 中启用此发行版"
fi

# 6. Java
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | head -1)
    pass "java: $JAVA_VER"
else
    fail "java 未安装：sudo apt install -y openjdk-17-jdk"
fi

# 7. Maven
if command -v mvn >/dev/null 2>&1; then
    pass "mvn: $(mvn --version 2>&1 | head -1)"
elif [[ -f "./mvnw" ]]; then
    pass "mvnw wrapper 可用（./mvnw）"
else
    warn "mvn 未安装：sudo apt install -y maven"
fi

# 8. Python
if command -v python3 >/dev/null 2>&1; then
    PY_VER=$(python3 --version)
    if python3 -c "import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)" 2>/dev/null; then
        pass "python3: $PY_VER"
    else
        warn "python3 版本过低（$PY_VER），推荐 3.11+"
    fi
else
    fail "python3 未安装：sudo apt install -y python3.11 python3.11-venv"
fi

# 9. CRLF 检查
echo ""
echo -e "${BOLD}--- 文件检查 ---${NC}"
CRLF_FILES=$(find . -name "*.sh" -not -path "./.git/*" -exec grep -lP '\r' {} \; 2>/dev/null || true)
if [[ -z "$CRLF_FILES" ]]; then
    pass "所有 .sh 文件均为 LF 换行"
else
    fail "以下 .sh 文件含 CRLF，需执行 dos2unix 修复：\n$CRLF_FILES"
fi

# 10. shell 脚本权限
NON_EXEC=$(find . -name "*.sh" -not -path "./.git/*" ! -executable 2>/dev/null | head -5 || true)
if [[ -z "$NON_EXEC" ]]; then
    pass "所有 .sh 文件均有执行权限"
else
    warn "以下 .sh 文件缺少执行权限（可执行 find . -name '*.sh' -exec chmod +x {} \\;）：\n$NON_EXEC"
fi

# 11. GPU
echo ""
echo -e "${BOLD}--- GPU ---${NC}"
if command -v nvidia-smi >/dev/null 2>&1; then
    GPU_INFO=$(nvidia-smi --query-gpu=name,memory.total --format=csv,noheader 2>/dev/null | head -1)
    pass "NVIDIA GPU：$GPU_INFO"
else
    warn "nvidia-smi 不可用，将使用 CPU 模式（Embedding/Reranker 推理会更慢）"
fi

# ── 汇总 ──
echo ""
echo -e "${BOLD}=== 检查结果：${GREEN}通过 $PASS${NC}  ${YELLOW}警告 $WARN${NC}  ${RED}失败 $FAIL${NC} ===${NC}"
echo ""
if [[ $FAIL -gt 0 ]]; then
    echo -e "${RED}存在失败项，请按提示修复后再启动服务。${NC}"
    exit 1
else
    echo -e "${GREEN}环境检查通过，可以继续启动服务。${NC}"
fi
