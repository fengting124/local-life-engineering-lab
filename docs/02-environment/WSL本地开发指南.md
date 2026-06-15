# WSL 本地开发指南

> 适用于：Ubuntu 22.04 on WSL2 + VS Code Remote WSL

---

## 1. WSL 项目路径规范

**必须把项目放在 Linux 文件系统**，不能在 `/mnt/c` 或 `/mnt/d`：

```
✅ 正确：~/projects/local-life   （即 /home/fengting/projects/local-life）
❌ 错误：/mnt/c/Users/xxx/projects/local-life
```

原因：Windows 挂载路径的文件 IO 要经过 Plan 9 协议转换，Maven 构建性能下降 5-10x，Docker volume mount 也会变慢。

---

## 2. VS Code Remote WSL

1. 安装 VS Code 扩展：**WSL** (ms-vscode-remote.remote-wsl)
2. 在 WSL 终端：`code .`（会自动用 Remote WSL 模式打开）
3. 确认左下角显示 `WSL: Ubuntu-22.04`

---

## 3. WSL 代理配置

项目使用 WSL mirrored networking，代理地址固定为 `127.0.0.1:9674`。

```bash
# 加到 ~/.bashrc 或 ~/.profile
export http_proxy=http://127.0.0.1:9674
export https_proxy=http://127.0.0.1:9674
export HTTP_PROXY=http://127.0.0.1:9674
export HTTPS_PROXY=http://127.0.0.1:9674
export all_proxy=http://127.0.0.1:9674
export ALL_PROXY=http://127.0.0.1:9674

# NO_PROXY 排除容器内部通信
export NO_PROXY=localhost,127.0.0.1,local-life-mysql,local-life-redis,local-life-milvus
```

验证代理：

```bash
curl -sI https://github.com | head -2
curl -sI https://huggingface.co | head -2
```

---

## 4. Docker Desktop WSL Integration

Docker Desktop 需要手动开启 WSL 集成：

1. Windows 端打开 Docker Desktop
2. Settings → Resources → WSL Integration
3. 勾选 `Ubuntu-22.04`（或你的发行版名称）
4. Apply & Restart

验证：

```bash
docker --version        # 应显示版本号
docker compose version  # 应显示 v2.x
```

---

## 5. 版本要求

| 工具 | 最低版本 | 安装方式 |
|------|----------|----------|
| Java | 17 | `sudo apt install openjdk-17-jdk` |
| Maven | 3.9 | `sudo apt install maven` 或 SDKMAN |
| Python | 3.10+ | 内置，推荐 3.11 |
| Docker | 24+ | Docker Desktop WSL Integration |
| Docker Compose | 2.20+ | 随 Docker Desktop |

安装基础工具（一次性）：

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven python3.11 python3.11-venv python3.11-dev \
    python3-pip dos2unix curl wget jq build-essential
```

---

## 6. 快速启动

```bash
cd ~/projects/local-life/infra
cp .env.example .env       # 填写 LLM_API_KEY，默认 deepseek-v4-flash
bash scripts/start-local.sh
```

脚本会自动：检查环境 → 启动 MySQL/Redis → 启动 RAG 服务

---

## 7. 常见问题

### `code: command not found`
```bash
# WSL 里手动安装 VS Code server
code .
# 如果不行，在 Windows 端 VS Code 命令面板：WSL: Connect to WSL
```

### `bad interpreter: /bin/bash^M`
CRLF 问题：
```bash
find . -name "*.sh" -exec dos2unix {} \;
find . -name "*.sh" -exec chmod +x {} \;
```

### `docker: command not found`
Docker Desktop 未启用 WSL Integration，见第 4 节。

### GitHub / HuggingFace 无法访问
```bash
# 检查代理是否启动（Windows 端代理软件是否运行）
echo $http_proxy
curl -sI https://github.com
```

### HuggingFace 模型下载慢
代理设置后仍慢时，可设置 HuggingFace 镜像：
```bash
export HF_ENDPOINT=https://hf-mirror.com
```

### CUDA 不可见（Docker 内）
Docker 内 GPU 需要 NVIDIA Container Toolkit：
```bash
# WSL 中安装
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
# 按官方文档安装 nvidia-container-toolkit
```

### 显存不足（OOM）
```bash
# 强制 CPU 模式
EMBEDDING_DEVICE=cpu RERANKER_DEVICE=cpu docker compose --profile rag up -d
```

### Milvus 启动慢
Milvus 依赖 etcd + minio 初始化，首次启动约 30-60 秒，等待 healthcheck 通过即可。

```bash
docker compose -f infra/docker-compose.dev.yml --profile rag ps
# STATUS 变为 healthy 后再继续
```
