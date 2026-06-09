# copilot-agent-service 测试文档

## 概述

本服务（Python + FastAPI）的测试全部为单元测试，使用 pytest + pytest-asyncio 运行。
所有外部依赖（Milvus、MySQL、MCP Server、DeepSeek API）均通过 `unittest.mock` 完全隔离。

**测试规模：142 条用例，6 个文件，100% 通过**

---

## 快速运行

```bash
cd copilot-agent-service
source .venv/bin/activate          # 激活虚拟环境（需先 pip install -r requirements.txt）

python -m pytest                   # 运行全部测试
python -m pytest -v                # 详细模式（显示每条用例名）
python -m pytest tests/test_guardrails.py   # 只跑某个文件
python -m pytest -k "fastpath"     # 按关键词过滤
```

`pytest.ini` 已配置 `asyncio_mode = auto`，异步测试无需手动加 `loop` 参数。

---

## 测试文件一览

| 文件 | 测试类数 | 用例数 | 被测模块 |
|------|---------|--------|---------|
| `test_guardrails.py` | 4 | 29 | `guardrails/input_checker.py` |
| `test_tool_router.py` | 4 | 27 | `agent/tool_router.py` |
| `test_mcp_client.py` | 5 | 22 | `mcp/mcp_client.py` |
| `test_rag_pipeline.py` | 5 | 29 | `rag/pipeline.py` |
| `test_bm25_store.py` | 4 | 16 | `rag/bm25_store.py` |
| `test_chat_api.py` | 3 | 19 | `api/chat.py` |
| **合计** | **25** | **142** | |

---

## 各测试文件说明

### test_guardrails.py（29 tests）

**被测逻辑**：Prompt Injection 防护 + 敏感信息输出过滤。

所有函数均为纯函数（无 I/O），直接调用测试，速度极快。

| 测试类 | 用例数 | 覆盖内容 |
|-------|--------|---------|
| `TestCheckInputBlock` | 13 | BLOCK 模式：忽略指令、DAN 越狱、角色扮演、泄露 Prompt、跨商家访问 |
| `TestCheckInputWarn` | 3 | WARN 模式：注入关键词、HTML `<script>`、HTML `<iframe>` |
| `TestCheckInputAllow` | 5 | 正常商家查询（不误杀）、空字符串 |
| `TestCheckOutput` | 8 | 输出过滤：手机号、DB 连接串、API 密钥、干净输出不被误过滤 |

**关键设计点**：
- `check_input()` 返回 `(action, reason)` 二元组，`action` 是 `"BLOCK" / "WARN" / "ALLOW"`
- BLOCK 在 Chat 端点最外层触发，早于任何 DB 操作
- 中英文 Prompt Injection 均有覆盖（"ignore all instructions" 和 "忽略所有指令"）

---

### test_tool_router.py（27 tests）

**被测逻辑**：RBAC 工具过滤 + 任务类型推断 + 高风险工具上下文门控。

`ToolRouter` 是纯内存操作（无网络、无 DB），直接实例化测试。

| 测试类 | 用例数 | 覆盖内容 |
|-------|--------|---------|
| `TestConcurrencySafe` | 8 | `is_tool_concurrency_safe()` 读/写/未知工具的安全分级 |
| `TestRbacFilter` | 6 | merchant/cs/admin/未知角色的工具可见性 |
| `TestTaskDetection` | 8 | 关键词 → 任务类型映射（diagnosis/analytics/campaign/knowledge/general）|
| `TestContextFilter` | 5 | 退款工具在无"已付款"上下文时隐藏；补券工具同理 |

**关键设计点**：
- **fail-closed 原则**：未登记工具 `is_tool_concurrency_safe()` 默认返回 `False`
- **任务类型推断**：dict 有序迭代（Python 3.7+），`diagnosis` 排在 `knowledge` 前面；
  测试中 `"退款"` 会命中 `diagnosis`，纯 knowledge 用例须用 `"限制"` 等无歧义关键词
- **上下文门控**：退款工具不仅 RBAC 限制 merchant，还需对话中出现 `"已付款/paid"` 才暴露

---

### test_mcp_client.py（22 tests）

**被测逻辑**：JSON-RPC 2.0 客户端，含错误解析、重试分级、TTL 工具缓存。

所有 HTTP 请求通过 `patch("httpx.AsyncClient")` mock，不发真实网络请求。

| 测试类 | 用例数 | 覆盖内容 |
|-------|--------|---------|
| `TestMcpToolError` | 8 | `is_retryable()`（parameter_error/tool_timeout 可重试，其余不可）、`to_dict()` |
| `TestMcpClientHeaders` | 5 | `X-User-Id/Role/Merchant-Id/Session-Id/Thread-Id` Header 构造 |
| `TestMcpClientRpc` | 4 | 超时→`tool_timeout`、HTTP 500→`internal_error`、JSON-RPC error 解析、成功路径 |
| `TestMcpClientCallTool` | 2 | `content[0].text` 提取、`content=[]` 时 fallback 到 `str(result)` |
| `TestListToolsCache` | 3 | 缓存未命中/命中/TTL 过期刷新 |

**关键 mock 模式**：`httpx.AsyncClient` 是异步上下文管理器，需同时设置 `__aenter__` 和 `__aexit__` 为 `AsyncMock`：

```python
mock_cm = MagicMock()
mock_cm.__aenter__ = AsyncMock(return_value=mock_client)
mock_cm.__aexit__ = AsyncMock(return_value=False)
```

**测试隔离**：`autouse=True` fixture 在每个测试前后清空进程级 `_tools_cache`，防止缓存污染。

---

### test_rag_pipeline.py（29 tests）

**被测逻辑**：RAG 检索管线——RRF 融合、文本分块、查询预处理、检索结果封装。

纯函数（`_rrf_merge / _split_text / _simple_rewrite / RagResult`）直接测试；
`retrieve()` 通过 `patch` mock 掉 embedding、Milvus、BM25、reranker。

| 测试类 | 用例数 | 覆盖内容 |
|-------|--------|---------|
| `TestRrfMerge` | 8 | RRF 公式、共同命中文档排名最高、去重、k 参数影响 |
| `TestSplitText` | 6 | 空字符串、短文本、滑动窗口边界、overlap 计算 |
| `TestSimpleRewrite` | 5 | 空白符压缩、制表符归一化 |
| `TestRagResult` | 5 | 拒答路径（`refused=True`）、上下文文本格式、截断、`to_dict()` |
| `TestRetrieve` | 5 | 无候选→拒答、reranker 全过滤→拒答、BM25+向量 RRF 融合、top_k 限制 |

**关键技术点**：
- RRF 公式：`score(d) = Σ 1/(k + rank_i(d))`，k=60
- BM25 `retrieve()` 中局部导入（`from rag.bm25_store import bm25_store`），需 patch `rag.bm25_store.bm25_store` 而非 `rag.pipeline.bm25_store`

---

### test_bm25_store.py（16 tests）

**被测逻辑**：BM25 关键词搜索，含 RBAC 权限过滤（public / merchant_private 文档可见性）。

每个测试使用全新 `BM25Store()` 实例，避免全局单例 `bm25_store` 的状态污染。

| 测试类 | 用例数 | 覆盖内容 |
|-------|--------|---------|
| `TestBm25StoreBasic` | 9 | add/build_index/search/clear、结果非空、无关查询得 0 分 |
| `TestBm25Permissions` | 3 | public 全局可见、private 仅归属商家可见、混合权限过滤 |
| `TestBm25TopK` | 2 | `top_k` 限制结果数 |
| `TestBm25ResultFormat` | 2 | 结果含必要字段（chunk_id/content/score 等）、按得分降序 |

**关键坑：BM25Okapi IDF 公式**

```
IDF = log((N - df + 0.5) / (df + 0.5))
```

当 `df = N`（所有文档都含该词）时 IDF ≤ 0，分数被库过滤为空结果。
规律：需 `N > 2 × df` 才能保证 IDF > 0。

实践原则：
- 每个测试查询词只在 **1 篇**目标文档中出现
- 凑够至少 **3 篇**文档（1 目标 + 2 decoy，且 decoy 不含该词）

混合权限测试（`test_mixed_scope_filters_correctly`）查询词 "退款" 出现在 2 篇文档，
需凑足 **6 篇**（df=2, N=6，IDF = log(1.8) ≈ 0.58 > 0）才能使两篇目标文档都得到正分。

---

### test_chat_api.py（19 tests）

**被测逻辑**：FastAPI Chat 端点——SSE 格式化、Fast Path 路由、Guardrails 拦截。

分层测试策略：
1. **`_sse()` 纯函数**：直接调用，验证 SSE 格式
2. **`_try_fast_path()` 异步函数**：mock `McpClient`，测路由逻辑
3. **`POST /chat` 端点**：用 `TestClient` 测 Guardrails 拦截路径

| 测试类 | 用例数 | 覆盖内容 |
|-------|--------|---------|
| `TestSseFormatter` | 6 | `event:/data:` 字段格式、双换行结尾、JSON 合法性、中文不转义 |
| `TestTryFastPath` | 9 | 非 merchant/无 merchant_id/无关键词 → None；今天/昨天指标 → 格式化答案；MCP 失败 → 静默 fallback |
| `TestChatEndpointGuardrails` | 4 | EN/CN Prompt Injection → 400；缺 Header → 422 |

**关键技术点**：
- `McpClient` 在 `_try_fast_path()` 函数体内局部导入，需 patch `mcp.mcp_client.McpClient`
- Fast Path 条件：`user_role=="merchant"` AND `merchant_id≠None` AND 含今天/昨天关键词 AND 含指标关键词
- `TestClient(app, raise_server_exceptions=False)` 确保 Guardrails 的 `HTTPException` 被捕获为正常响应

---

## 测试设计原则

### 1. 完全隔离外部依赖

所有 I/O 均通过 mock：
- HTTP（httpx）：patch `httpx.AsyncClient`
- 向量数据库：patch `rag.pipeline._get_vector_store`
- Embedding 模型：patch `rag.pipeline.embed_query`
- Reranker：patch `rag.pipeline.rerank`
- BM25 全局实例：patch `rag.bm25_store.bm25_store`

### 2. 局部导入与 patch 目标

Python 中若被测函数使用 `from x.y import z`，应 patch `x.y.z`，而不是 `calling_module.z`。
在本项目中：

| 被 mock 的对象 | 正确 patch 路径 |
|--------------|---------------|
| `McpClient` | `mcp.mcp_client.McpClient` |
| `bm25_store` | `rag.bm25_store.bm25_store` |

### 3. 异步测试

pytest.ini 配置 `asyncio_mode = auto`，所有 `async def test_*` 自动被 asyncio 驱动，
无需 `@pytest.mark.asyncio` 装饰（但显式写也不报错）。

### 4. 避免全局单例污染

- `BM25Store`：每个测试 `store = BM25Store()` 新建实例
- `mcp._tools_cache`：`autouse` fixture 在每个测试前后清空
- 不改动 `bm25_store`（全局单例）——只在需要时 patch 它

---

## 依赖

```
pytest>=9.0
pytest-asyncio>=0.23
pytest-mock>=3.15
anyio
httpx
fastapi
starlette
rank-bm25
```

安装：

```bash
pip install -r requirements.txt
```
