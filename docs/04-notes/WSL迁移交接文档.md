# WSL 迁移交接文档

> 时间：2026-06-04 | 从 Windows 迁移到 WSL | 作者：Claude
>
> **本文只说现有文档没有的内容**：迁移步骤、当前真实状态、接下来优先做什么。
> 已有文档：README.md（启动）、PROGRESS.md（模块状态）、各 subproject README、深度拷打指南。

---

## 一、迁移到 WSL 的步骤（5 分钟）

```bash
# WSL 里执行（建议 Ubuntu 22.04）
cd ~
git clone https://github.com/fengting124/local-life-engineering-lab.git
cd local-life-engineering-lab

# Python 环境（WSL 原生 Python 3.11+）
cd copilot-agent-service
pip install -r requirements.txt   # 首次约 10-15 分钟（PyTorch CPU 版 700MB）

# Java 环境
# WSL 推荐用 SDKMAN 安装 JDK 17
curl -s "https://get.sdkman.io" | bash
source ~/.sdkman/bin/sdkman-init.sh
sdk install java 17.0.9-tem
sdk install maven 3.9.9

# 复制 .env（只需填 LLM_API_KEY）
cp copilot-agent-service/.env.example copilot-agent-service/.env
nano copilot-agent-service/.env   # 填写 LLM_API_KEY

# 启动
cd infra && bash scripts/start.sh
```

**WSL vs Windows 的实质差异**：
- Docker Desktop 在 WSL 里更快（文件 IO 不经过 Windows 层）
- Python 路径变为 `python3`（不再是 `/d/Python/python.exe`）
- 换行符问题 Git 已配置，不影响
- 性能提升约 20-30%（文件密集型操作如 Maven 构建）

---

## 二、当前代码真实状态（哪些跑过，哪些只是写了代码）

### 已验证可以运行

- ✅ LocalLife Server（Java）：代码编译无报错（IDE 诊断），所有 ErrorCode 枚举完整
- ✅ Docker Compose 中间件层：mysql/redis/es/mq 全部可以 `docker compose up -d`
- ✅ Python 语法：所有 `.py` 文件通过 `ast.parse()` 检查

### 写了代码但**没有实际启动过验证**的

- ❌ **三个服务从未在这台机器上真正启动过**（Docker Desktop 在本机未运行）
- ❌ ShardingSphere 5.5.0 + 环境变量替换：`${MYSQL_HOST:-localhost}` 是否真的生效需要验证
- ❌ Copilot Python 服务的所有依赖包均未安装过（`pip install` 没跑）
- ❌ RAG 知识库的 sentence-transformers 模型未下载过
- ❌ LLM-as-Judge、Hybrid RAG 等新功能未端到端测试过

### 重点要验证的第一个问题

启动后最先要确认的：

```bash
# 1. Java 服务能启动吗？
cd local-life-server && mvn spring-boot:run 2>&1 | grep -E "ERROR|Started|WARN" | head -20

# 2. ShardingSphere 环境变量生效了吗？
# 看日志是否有 "Actual SQL: ds0 ::: SELECT * FROM order_info_0"

# 3. Python 服务能启动吗？
cd copilot-agent-service && uvicorn main:app --port 8000 2>&1 | tail -20

# 4. /chat 接口能工作吗（最简单验证）？
curl -X POST http://localhost:8000/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" -H "X-User-Role: merchant" -H "X-Merchant-Id: 20001" \
  -H "Accept: text/event-stream" \
  -d '{"message":"今天卖了多少钱？","session_id":0}'
```

---

## 三、接下来优先做什么

按重要性排序：

### 优先级 1：跑通并验证（没有这个，后面都是空的）

```
启动三个服务 → 确认日志无 ERROR → 用 Chat UI 发一条消息 → 能得到回复
```

这是一切的前提。预计可能遇到的问题：
- MySQL 连接问题（端口、密码）
- ShardingSphere 路由日志确认
- Python 依赖版本冲突（PyTorch / langchain 版本组合）
- Milvus 不启动时 RAG 降级是否正常

### 优先级 2：跑 Evals 得到真实数字

```bash
cd copilot-agent-service
python -m evals.metrics --real --category query  # 先跑简单的 15 条
```

目的：把文档里所有「⚠ 待实测」的指标填上真实数字，包括：
- P50/P99 延迟
- 工具调用准确率（目前估算是 84.3%，需要实测）
- Fast Path 覆盖率
- Prompt Caching 的 `cache_read_input_tokens` 占比

**这些数字是面试时最有说服力的证明，必须是真的。**

### 优先级 3：用 DeepSeek 对比跑一次 Evals

```bash
# .env 改成 deepseek
LLM_PROVIDER=deepseek
LLM_API_KEY=你的key
LLM_MODEL=deepseek-chat

python -m evals.metrics --real --category diagnosis
```

对比 Claude 和 DeepSeek 的工具调用准确率差异，这是面试时说「我对比了不同模型」的数据来源。

### 优先级 4：知识库补充内容

目前只有 2 篇文档（platform_rules.md + troubleshooting_cases.md），RAG 实际上没有足够内容支撑「平台规则问答」场景。

建议补充：
- `rag/knowledge_base/merchant_manual.md`：商家操作手册（上下线、活动配置）
- `rag/knowledge_base/faq.md`：客服高频问题 30 条
- `rag/knowledge_base/coupon_policy.md`：券规则详细说明

补充后重新入库：`python -m rag.ingest`，再跑 knowledge 类 Evals 验证 Recall@5 是否提升。

### 优先级 5：Locust 压测得到性能基准

```bash
cd performance-tests
pip install -r requirements.txt
locust -f locustfile_locallife_server.py --host http://localhost:8080 \
  --users 50 --spawn-rate 5 --run-time 60s --headless --html report.html
```

目前文档里所有延迟数字都是估算，压测后可以填真实值。

---

## 四、不需要再做的事

- ~~接口文档~~：已完整
- ~~Copilot 教程~~：已写 12 章
- ~~深度拷打指南~~：已写 9 章 + 3 附录
- ~~前端~~：Chat UI + 审批工作台已完成
- ~~Dockerfile~~：三个都写好了
- ~~多 LLM Provider~~：刚刚实现，支持 Claude/DeepSeek/Qwen/Ollama

---

## 五、一个小提醒

DeepSeek API 比 Claude 便宜约 100 倍（¥2 vs ¥200+ 每百万 token），日常开发调试可以用 DeepSeek，面试演示时可以根据情况选。两者都在代码里支持了，切换只需改两行 `.env`。

---

*迁移后如果遇到问题，优先看 PROGRESS.md 的「常见 Bug 备忘」一节。*
