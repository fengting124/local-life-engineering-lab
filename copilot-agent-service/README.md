# copilot-agent-service（Python Agent Service）

> LocalLife Copilot 的 AI 大脑 | LangGraph ReAct Agent | 端口 8000

---

## 职责定位

```
前端（Chat UI / 审批工作台）
    ↓ HTTP + SSE
copilot-agent-service（:8000）  ← 本服务
  ├── LangGraph ReAct Agent（多步推理）
  ├── MCP Client → local-life-copilot（:8081）
  ├── RAG Pipeline → Milvus（:19530）
  └── Session/Message → MySQL

本服务负责：编排 + 检索 + 决策 + 流式输出
不负责：业务规则（在 Java 主服务）、工具权限（在 MCP Server）
```

---

## 快速启动

```bash
cd copilot-agent-service

# 1. 配置环境变量
cp .env.example .env
# 编辑 .env，至少填写 LLM_API_KEY；默认 provider/model 是 deepseek/deepseek-v4-flash

# 2. 安装依赖（建议用 Python 3.11+）
pip install -r requirements.txt

# 3. 入库知识库（Milvus 已启动时执行，首次必须）
python -m rag.ingest

# 4. 启动服务
uvicorn main:app --host 0.0.0.0 --port 8000 --reload

# 验证
curl http://localhost:8000/health
```

在线接口文档：

- FastAPI 原生 Swagger：`http://localhost:8000/docs`
- OpenAPI JSON：`http://localhost:8000/openapi.json`
- Java/Springdoc 兼容入口：`http://localhost:8000/swagger-ui.html`
- Java/Springdoc 兼容 JSON：`http://localhost:8000/v3/api-docs`

**最低运行环境**：只需 MySQL + Redis + local-life-copilot（:8081）。
Milvus 非必须（不可用时 knowledge_search 返回 Mock 结果，其他功能正常）。

---

## API 接口完整参考

### 会话管理

#### POST /sessions — 创建新会话

```
Headers:
  X-User-Id:     10001
  X-User-Role:   merchant        # merchant / cs / admin
  X-Merchant-Id: 20001           # merchant 角色必填

Body:
  {"initial_message": "首条消息（用于生成标题，可选）"}

Response:
  {"session_id": "123456789", "title": "...", "status": "ACTIVE", ...}
```

#### GET /sessions — 列出当前用户会话

```
Headers: X-User-Id

Response: {"count": 3, "sessions": [{...}]}
```

#### GET /sessions/{id} — 会话详情

#### POST /sessions/{id}/end — 结束会话

---

### 对话（SSE 流式输出）

#### POST /chat — 发起对话

```
Headers:
  X-User-Id, X-User-Role, X-Merchant-Id（同上）
  Accept: text/event-stream

Body:
  {
    "message":    "我今天卖了多少钱？",
    "session_id": 0,              # 0 = 自动创建新会话
    "thread_id":  null            # null = 自动生成
  }
```

#### SSE 事件类型（按发送顺序）

| event | data 字段 | 说明 |
|-------|----------|------|
| `session_started` | `{session_id, thread_id}` | 立即推送，前端记录 ID |
| `agent_step` | `{step, node}` | Agent 进入新节点（如 llm_node） |
| `stream` | `{content}` | LLM 流式输出文字片段 |
| `tool_call` | `{tool, args}` | 即将调用工具 |
| `tool_result` | `{tool, result}` | 工具返回结果（截断到 500 字） |
| `hitl_request` | `{thread_id, action, message}` | 高风险动作请求审批，Agent 挂起 |
| `final_answer` | `{content, stop_reason, thread_id}` | 最终回答，流结束 |
| `error` | `{message}` | 异常（前端展示错误提示） |

`stop_reason` 可能值：`completed` / `max_steps` / `token_budget` / `pending_approval`

#### POST /chat/resume — 审批通过后恢复 Agent

```
Body:
  {
    "thread_id":   "...",    # 挂起时的 thread ID
    "approval_id": "...",    # 审批记录 ID
    "approved":    true      # false = 拒绝，流会推送 final_answer 并结束
  }

Response: SSE 流（同 /chat，继续推送 Agent 后续步骤）
```

---

### HITL 审批工作台（运营人员使用）

#### GET /hitl/pending — 待审批列表

```
Headers: X-User-Id, X-User-Role（必须 cs 或 admin）

Response:
  {
    "count": 2,
    "approvals": [{
      "id": 123, "action_type": "execute_refund",
      "action_payload": {"order_id": "xxx", "amount": 2990},
      "agent_reason": "支付成功但券发放失败，库存耗尽，退款合理",
      "expire_at": "2026-05-29T20:00:00"
    }]
  }
```

#### POST /hitl/{id}/approve — 通过审批

```
Body: {"comment": "已核实，退款合理"}
Response: {"status": "approved", "thread_id": "...", "message": "请调用 /chat/resume 恢复 Agent"}
```

#### POST /hitl/{id}/reject — 拒绝审批

---

### 系统接口

| 接口 | 说明 |
|------|------|
| `GET /health` | 健康检查 |
| `GET /metrics` | Prometheus 指标 |

---

## 知识库管理

```bash
# 入库整个 knowledge_base/ 目录
python -m rag.ingest

# 入库指定文件（public 文档）
python -m rag.ingest --file rag/knowledge_base/platform_rules.md

# 入库商家私有文档（仅该商家可检索）
python -m rag.ingest \
  --file /path/to/private_doc.md \
  --scope merchant_private \
  --merchant 20001
```

**知识库文档格式**：Markdown，第一行 `# 标题`，无格式限制。

---

## Agent 行为说明

### ReAct 循环

```
用户提问
  → llm_node（Thought + Action）
  → tool_node（执行 MCP 工具 或 knowledge_search）
  → llm_node（分析 Observation，决定下一步）
  → ... 循环 ...
  → final_node（输出最终回答）
```

### 6 种终止条件

| 条件 | 触发 | stop_reason |
|------|------|-------------|
| 任务完成 | LLM 给出 Final Answer | `completed` |
| 最大步数 | step_count ≥ 15 | `max_steps` |
| Token 预算 | token_count ≥ 50000 | `token_budget` |
| HITL 挂起 | 触发高风险动作 | `pending_approval` |
| 任务完成（最终节点） | final_node 执行 | `completed` |

### Tool Router（动态工具过滤）

根据用户角色 + 消息关键词 + 对话历史，每次推理只给 LLM 4-8 个相关工具，
而不是全部 10 个。避免无关工具干扰决策、浪费 Token。

### Self-Reflection

每 5 步触发一次（或工具失败时立即触发），Agent 会自问：
- 已收集的证据够了吗？
- 是否在重复无效操作？
- 下一步最优路径是什么？

---

## 代码结构

```
copilot-agent-service/
├── main.py                   FastAPI 入口 + Prometheus + 路由注册
├── config/settings.py        Pydantic Settings（所有配置从环境变量读取）
├── agent/
│   ├── graph.py              LangGraph StateGraph 定义（节点 + 条件路由）
│   ├── state.py              AgentState TypedDict（完整状态结构）
│   ├── nodes.py              5 个节点实现（llm/tool/reflection/hitl/final）
│   ├── tool_router.py        动态工具过滤（三层：角色/任务/上下文）
│   └── metrics.py            Prometheus 业务指标（12 个 Counter/Histogram）
├── api/
│   ├── chat.py               POST /chat（SSE）+ POST /chat/resume（HITL 恢复）
│   ├── session.py            Session CRUD API
│   └── hitl.py               审批工作台 API
├── mcp/mcp_client.py         异步 HTTP 客户端（调用 Java MCP Server）
├── rag/
│   ├── pipeline.py           完整 RAG 流水线（Query→Embed→Search→Rerank）
│   ├── embedding.py          multilingual-e5-base 向量化
│   ├── vector_store.py       Milvus CRUD + 权限过滤
│   ├── reranker.py           Cross-Encoder 精排
│   ├── knowledge_tool.py     LangChain 原生工具（绑定 merchant_id）
│   ├── ingest.py             知识库入库 CLI
│   └── knowledge_base/       Markdown 文档（public + merchant_private）
├── session/
│   ├── manager.py            会话 + 消息 CRUD（aiomysql async）
│   ├── models.py             SQLAlchemy AgentSession + AgentMessage
│   ├── checkpointer.py       AsyncMySQLCheckpointer（HITL 恢复关键）
│   └── hitl.py               HitlApproval 模型 + HITL 服务
├── guardrails/
│   └── input_checker.py      BLOCK/WARN/ALLOW 三级输入检测（12+ 规则）
└── evals/
    ├── eval_cases.py         50 条评测用例（4 类）
    ├── metrics.py            6 项指标计算 + CLI 入口
    └── real_agent_client.py  SSE 消费真实 Agent 输出
```

---

## 评测运行

```bash
# Mock 模式（验证评测框架本身，不调用真实 LLM）
python -m evals.metrics

# 真实模式（需启动完整服务 + LLM_API_KEY，会产生 API 费用）
python -m evals.metrics --real

# 只跑订单异常排查（15 条用例）
python -m evals.metrics --real --category diagnosis
```

输出示例：
```
Metric 1 | Tool Call Accuracy:   0.843
Metric 2 | Task Completion Rate: 0.880
Metric 3 | Recall@5 (knowledge): 0.733
Metric 4 | Factual Consistency:  0.867
Metric 5 | Latency P50/P99:      3200ms / 18500ms
Metric 6 | Avg Tokens/Session:   2840
```
