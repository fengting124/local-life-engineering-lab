# LocalLife Claude Code 工作流规则

## 总原则

本项目不使用 OpenAI Agents SDK、Claude Agent SDK、ADK、额外 API 自动化框架。

所有维护工作都基于本地 Claude Code 交互式工作流完成。

目标是标准化任务流程，减少反复沟通成本，让 Claude Code 在本地完成分析、改动、测试、冲突处理、文档同步和复盘。

禁止默认引入任何新的 agent 框架、后台服务、API key、远程自动化平台。

## 项目结构认知

本项目是双线项目：

1. local-life-server
   Java Spring Boot 主服务，重点展示 MySQL、Redis、RocketMQ、Elasticsearch、高并发、一致性、分表、限流、事务、缓存。

2. local-life-copilot
   Java MCP Server，负责把 LocalLife 业务能力封装成 MCP JSON-RPC 2.0 工具。

3. copilot-agent-service
   Python FastAPI Agent Service，负责 LangGraph、RAG、HITL、Evals、Guardrails、SSE 流式输出。

4. infra
   Docker Compose、本地中间件、启动脚本、Prometheus、Grafana、Zipkin。

5. performance-tests
   Locust 压测脚本。

6. docs
   项目架构、接口规范、环境搭建、深度教程、面试材料。

两条业务线共享同一套数据库和业务语义。修改任何一侧时，必须检查是否影响另一侧。

## 工作方式

每次接到任务后，先判断任务类型：

1. 测试类任务
2. Bug 修复类任务
3. 冲突解决类任务
4. 大规模数据验证类任务
5. CI 失败排查类任务
6. 数据库迁移类任务
7. Copilot Agent 回归类任务
8. 文档同步类任务
9. 部署发布准备类任务

然后按对应流程执行。

不要一上来写代码。先读相关文件，确认影响范围，再改动。

每次修改前必须说明：

1. 问题根因
2. 涉及模块
3. 最小改动方案
4. 风险点
5. 验证命令

每次修改后必须给出：

1. 改了哪些文件
2. 为什么这样改
3. 跑了哪些测试
4. 测试结果
5. 是否还有遗留风险

## 禁止行为

禁止引入以下内容：

1. OpenAI Agents SDK
2. Claude Agent SDK
3. ADK
4. 新的自动化 agent 服务
5. 新的外部 API key
6. 新的云端自动化平台
7. 自动部署脚本
8. 自动 merge PR
9. 自动推送 main
10. 自动修改 secrets
11. 自动修改 .env
12. 自动删除 migration
13. 自动绕过 HITL
14. 自动改生产配置

除非用户明确要求，不要新增技术栈。

优先使用项目已有工具：

1. Maven
2. Pytest
3. Docker Compose
4. MySQL CLI
5. Redis CLI
6. curl
7. git
8. gh CLI
9. bash scripts
10. PowerShell scripts
11. 项目已有 evals
12. 项目已有 docs

## local-life-server 测试流程

修改 local-life-server 后，按影响范围选择测试。

### Web 层或 Controller 修改

运行：

```bash
cd local-life-server
mvn -Dtest=AuthControllerTest,ShopControllerTest,PostControllerTest test
```

检查：

1. 请求绑定是否正确
2. 参数校验是否正确
3. 响应包装是否正确
4. HTTP 状态码是否正确
5. 鉴权上下文是否正确

### 鉴权拦截器修改

运行：

```bash
cd local-life-server
mvn -Dtest=AuthInterceptorTest test
```

检查：

1. 白名单端点
2. HTTP 方法匹配
3. 受保护接口
4. token 缺失场景
5. Bug 回归场景

### 登录、Redis、用户状态修改

运行：

```bash
cd local-life-server
mvn -Dtest=AuthJourneyIntegrationTest test
```

检查：

1. 发验证码
2. 登录或自动注册
3. 访问受保护资源
4. 登出
5. token 失效
6. Redis key 命名
7. LoginUserDTO 序列化
8. 数据库回滚后无残留

### 全量验证

运行：

```bash
cd local-life-server
mvn test
```

通过标准：

```text
BUILD SUCCESS
所有测试通过
无数据库残留
无 Redis 残留
```

## 大规模多数据测试流程

当用户要求做大规模、多数据、多场景测试时，必须按以下流程执行：

### 第一步：列测试矩阵

先列出测试维度。

示例：

```text
接口维度：
验证码、登录、登出、店铺、帖子、评论、点赞、关注、优惠券、秒杀、订单、支付

用户维度：
未登录用户
普通用户
商家用户
非法用户
token 过期用户

数据维度：
正常数据
空数据
边界数据
重复数据
非法数据
并发数据

依赖维度：
MySQL 正常
Redis 正常
缓存未命中
缓存命中
事务回滚
```

### 第二步：确认最小覆盖集

优先覆盖这些问题：

1. 鉴权失效
2. 参数绑定错误
3. 响应格式漂移
4. Redis key 不一致
5. 数据库事务未回滚
6. 分表路由错误
7. 幂等失效
8. 缓存和数据库不一致
9. MQ outbox 状态错误
10. HITL 状态错误

### 第三步：补测试

测试分层：

```text
单测：验证单个组件逻辑
切片测试：验证 Web 层绑定、校验、包装
集成测试：验证 MySQL、Redis、ShardingSphere、事务、序列化真实协作
Evals：验证 Copilot Agent 行为
压测：验证高并发性能和一致性
```

### 第四步：跑测试

优先从小到大：

```bash
mvn -Dtest=具体测试类 test
mvn test
```

Python Agent：

```bash
cd copilot-agent-service
pytest
```

压测：

```bash
cd performance-tests
locust
```

### 第五步：输出结果

结果必须包含：

1. 测试总数
2. 通过数
3. 失败数
4. 新增测试类
5. 新增测试场景
6. 发现的问题
7. 已修复的问题
8. 未修复风险

## 冲突解决流程

遇到 git conflict 时，不要直接覆盖。

必须按以下流程：

### 第一步：查看冲突范围

运行：

```bash
git status
git diff --name-only --diff-filter=U
```

### 第二步：逐文件判断冲突类型

分类：

1. 纯格式冲突
2. import 冲突
3. 配置冲突
4. 测试冲突
5. 业务逻辑冲突
6. SQL migration 冲突
7. 文档冲突
8. workflow 冲突

### 第三步：保留语义

解决冲突时必须保留双方有效语义。

不能简单选择 ours 或 theirs，除非确认另一侧完全废弃。

### 第四步：高风险文件单独说明

以下文件冲突必须单独说明：

```text
payment
seckill
outbox
sharding
rate_limit
auth
hitl
checkpointer
tool_router
rag
evals
docker-compose
migration
GitHub Actions workflow
```

### 第五步：冲突后测试

按模块运行测试。

Java：

```bash
cd local-life-server
mvn test
```

Copilot Java：

```bash
cd local-life-copilot
mvn test
```

Python：

```bash
cd copilot-agent-service
pytest
```

### 第六步：输出冲突处理报告

必须输出：

1. 冲突文件
2. 冲突原因
3. 保留了哪边语义
4. 删除了哪边语义
5. 是否有风险
6. 已跑测试
7. 测试结果

## 数据库迁移规则

迁移文件高风险。

修改 migration 前必须检查：

1. 文件名版本号是否冲突
2. 是否会重复执行
3. 是否有 IF NOT EXISTS
4. 是否有 IF EXISTS
5. 是否影响旧环境
6. 是否影响新环境从零初始化
7. 是否影响 CI 空库初始化
8. 是否影响 Copilot 表

当前约定：

```text
local-life-server 使用 V1 到 V99
local-life-copilot 使用 V101 到 V199
shared 或 infra 使用 V201 到 V299
```

发现版本号冲突时，优先改未被成功追踪的新迁移文件名。

不要轻易修改 schema_migrations 追踪规则。

不要轻易改历史 migration 的语义。

每次迁移修复后，必须用全新空白 MySQL 验证：

```bash
bash infra/scripts/start.sh
```

然后检查：

```sql
SELECT * FROM schema_migrations ORDER BY executed_at;
SHOW TABLES;
```

Copilot 初始化必须确认：

```sql
SHOW TABLES LIKE 'agent_%';
SHOW TABLES LIKE 'hitl_%';
```

## CI 失败排查流程

CI 失败时，按以下顺序排查：

1. 查看失败 job
2. 查看失败 step
3. 查看 Maven 或 Pytest 日志
4. 查看 Surefire reports
5. 判断失败类型
6. 找最小修复点
7. 本地复现
8. 修复
9. 本地重跑
10. 输出报告

失败类型分类：

```text
Java 编译失败
Java 单测失败
Java 集成测试失败
Python 单测失败
Docker Compose 启动失败
MySQL 初始化失败
Redis 连接失败
migration 失败
ShardingSphere 配置失败
MCP tools/list 失败
Agent SSE 失败
Evals 失败
```

CI 失败不要直接猜。

必须读取日志和相关测试报告。

## Copilot 回归流程

修改以下内容时必须考虑 Copilot 回归：

```text
local-life-copilot
copilot-agent-service
MCP tool schema
session
hitl
checkpointer
tool_router
rag
reranker
evals
SSE
approval
```

检查点：

1. MCP tools/list 是否正常
2. 工具参数 schema 是否兼容
3. Header 鉴权是否正确
4. Agent 是否能调用 MCP
5. HITL 是否能挂起
6. HITL 是否能恢复
7. MySQL checkpoint 是否持久化
8. RAG 低置信度是否拒答
9. SSE 是否持续输出步骤
10. eval cases 是否通过

验证命令：

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8000/health
```

MCP 验证：

```bash
curl -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -H "X-User-Role: merchant" \
  -H "X-Merchant-Id: 20001" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}'
```

Agent 验证：

```bash
curl -X POST http://localhost:8000/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -H "X-User-Role: merchant" \
  -H "X-Merchant-Id: 20001" \
  -H "Accept: text/event-stream" \
  -d '{"message":"我今天卖了多少钱？","session_id":0}'
```

## 文档同步规则

代码变更后必须检查文档是否需要同步。

映射关系：

```text
Controller 变更
→ docs/01-project/10-接口规范文档.md

MCP tool 变更
→ docs/01-project/11-Copilot架构设计文档.md

数据库 migration 变更
→ docs/02-environment/01-环境搭建.md
→ README.md

LangGraph、RAG、HITL、Evals 变更
→ docs/04-notes/LocalLifeCopilot项目教程.md

Java 后端核心机制变更
→ docs/04-notes/LocalLife项目接口教程.md

项目进度变更
→ PROGRESS.md
```

每次完成任务后，必须判断：

```text
README 是否过期
接口文档是否过期
环境搭建文档是否过期
教程是否过期
PROGRESS.md 是否过期
```

## 部署发布准备规则

当前仓库没有成熟部署发布 workflow。

因此部署相关任务只允许做：

1. 生成发布计划
2. 生成 release notes
3. 检查变更风险
4. 检查配置项
5. 检查启动脚本
6. 检查数据库迁移
7. 检查回滚方案

禁止直接触发生产部署。

发布计划必须包含：

1. 版本范围
2. 变更摘要
3. 数据库变更
4. 配置变更
5. 中间件依赖
6. 测试结果
7. 风险点
8. 回滚方式
9. 人工确认项

## HITL 思路

本项目已经在 Copilot 中实现 HITL。DevOps 维护也采用同样思想。

低风险动作可以直接做：

```text
读取文件
搜索代码
运行测试
查看日志
生成报告
修改文档
补充测试
```

中风险动作需要先说明再做：

```text
修改业务代码
修改 migration
修改 workflow
修改 docker-compose
批量重构
```

高风险动作只做提案：

```text
部署
删除文件
合并 PR
推送 main
修改 secrets
修改生产配置
修改支付、秒杀、outbox、分表逻辑
```

## 每次任务的固定输出格式

任务开始时输出：

```text
任务类型：
影响范围：
计划步骤：
预计验证方式：
```

执行中发现问题时输出：

```text
发现问题：
证据：
影响：
处理方案：
```

任务完成时输出：

```text
完成内容：
修改文件：
验证命令：
验证结果：
遗留风险：
下一步建议：
```

不要只说"已修复"。

必须给证据。

## 面试价值优先级

做任何增强时，优先服务于项目展示价值。

高价值方向：

1. Java 后端工程能力
2. 分布式一致性
3. 高并发
4. 缓存一致性
5. MQ outbox
6. 分库分表
7. Agent 工程能力
8. MCP
9. LangGraph
10. HITL
11. RAG
12. Evals
13. Guardrails
14. CI 测试体系
15. 文档和可复现部署

低价值方向：

1. 为了炫技引入新框架
2. 新增未使用的 SDK
3. 过早自动部署
4. 过早后台 agent 化
5. 大量抽象但没有测试
6. 做无法在面试中解释的复杂自动化

## 当前优先任务建议

项目当前优先级：

1. 保持 local-life-server 93 个测试全绿
2. 修复 migration 版本号冲突
3. 给 copilot 建立测试体系
4. 给 MCP tools/list 做 schema 快照测试
5. 给 Agent Evals 做回归测试
6. 给 README 和 docs 建立同步检查
7. 只在本地 Claude Code 中标准化维护流程
8. 暂不使用任何外部 SDK 或 API 自动化

## Git 分支工作流规则

当前项目只有一个长期稳定分支：

```text
main
```

main 代表可运行、可测试、可展示的稳定版本。

Claude Code 不允许直接在 main 上修改代码。

每次开始正式任务前，必须先创建任务分支。

## 分支总原则

一项任务一个分支。

禁止多个无关任务混在同一个分支里。

禁止直接 push main。

禁止自动 merge PR。

禁止自动删除远程分支，除非用户明确要求。

禁止在工作区有未提交改动时强行切分支。

## 开始任务前检查

每次任务开始前先执行：

```bash
git status
git branch --show-current
```

如果当前在 main，并且工作区干净，按任务类型创建新分支。

如果当前不在 main，先判断当前分支是否就是本任务分支。

如果工作区有未提交改动，先说明：

```text
当前存在未提交改动，不能直接切换分支。
需要先判断这些改动属于当前任务、历史残留，还是应该暂存。
```

不要擅自执行 `git reset --hard`。

不要擅自丢弃用户改动。

## 分支命名规则

使用短横线命名。

格式：

```text
类型/模块-任务描述
```

常用类型：

```text
fix      修 bug
test     补测试
docs     改文档
ci       改 GitHub Actions 或测试流水线
chore    清理、重命名、配置微调
feat     新功能
refactor 重构
perf     性能优化
infra    Docker、脚本、中间件、环境
copilot  Copilot/MCP/Agent 相关
```

示例：

```text
fix/copilot-migration-version
test/local-life-server-auth-journey
ci/local-life-server-workflow-dispatch
docs/update-copilot-workflow
copilot/mcp-tools-schema-snapshot
infra/docker-compose-health-check
refactor/auth-interceptor-whitelist
```

## 分支选择规则

### local-life-server 相关

修改 Java 后端业务代码：

```bash
git checkout -b fix/local-life-server-具体问题
```

补 Java 测试：

```bash
git checkout -b test/local-life-server-测试目标
```

涉及高并发、一致性、缓存、MQ、分表：

```bash
git checkout -b fix/server-consistency-具体问题
```

### local-life-copilot 相关

修改 MCP Server：

```bash
git checkout -b copilot/mcp-具体问题
```

修改 MCP tools/list、schema、权限 Header：

```bash
git checkout -b copilot/mcp-tools-schema
```

### copilot-agent-service 相关

修改 LangGraph、HITL、RAG、Evals、SSE：

```bash
git checkout -b copilot/agent-具体问题
```

补 Agent evals：

```bash
git checkout -b test/copilot-agent-evals
```

### infra 相关

修改 Docker Compose、启动脚本、中间件：

```bash
git checkout -b infra/具体问题
```

### CI 相关

修改 GitHub Actions：

```bash
git checkout -b ci/具体问题
```

例如：

```bash
git checkout -b ci/local-life-server-workflow-dispatch
```

### 文档相关

只改 README、docs、PROGRESS：

```bash
git checkout -b docs/具体主题
```

### 数据库迁移相关

修改 migration：

```bash
git checkout -b fix/migration-具体问题
```

例如：

```bash
git checkout -b fix/copilot-migration-version
```

migration 属于高风险改动，必须单独分支，不要和其他功能混在一起。

## 标准任务流程

### 第一步：确认当前状态

```bash
git status
git branch --show-current
```

### 第二步：创建任务分支

如果当前在 main 且工作区干净：

```bash
git checkout -b 类型/模块-任务描述
```

### 第三步：执行任务

按 CLAUDE.md 对应流程：

```text
读文件
分析问题
给出最小方案
修改代码
运行测试
检查 diff
同步文档
```

### 第四步：检查改动

```bash
git diff
git status
```

必须说明：

```text
修改文件：
修改原因：
风险点：
```

### 第五步：运行测试

按影响范围运行测试。

local-life-server：

```bash
cd local-life-server
mvn test
```

local-life-copilot：

```bash
cd local-life-copilot
mvn test
```

copilot-agent-service：

```bash
cd copilot-agent-service
pytest
```

infra 或启动链路：

```bash
bash infra/scripts/start.sh
```

### 第六步：提交

提交前先看 diff：

```bash
git diff --stat
git diff
```

提交信息格式：

```text
类型(模块): 简短说明
```

示例：

```text
fix(copilot): avoid migration version collision
test(server): add auth journey integration coverage
ci(server): add workflow dispatch trigger
docs(project): document branch workflow
```

提交：

```bash
git add .
git commit -m "fix(copilot): avoid migration version collision"
```

### 第七步：推送分支

只推送当前任务分支：

```bash
git push origin 当前分支名
```

不要 push main。

### 第八步：创建 PR

可以生成 PR 标题和正文。

如果用户允许，可以执行：

```bash
gh pr create --base main --head 当前分支名 --title "..." --body "..."
```

创建 PR 前必须输出 PR 内容给用户确认。

不要自动 merge。

## PR 正文模板

每次生成 PR 时使用：

```md
## 变更内容

-

## 问题根因

-

## 修复方案

-

## 验证结果

​```bash
# 执行过的命令
​```

结果：

​```text
# 测试结果
​```

## 风险说明

*

## 是否需要文档同步

* [ ] README
* [ ] docs/01-project
* [ ] docs/02-environment
* [ ] docs/04-notes
* [ ] PROGRESS.md
```

## 多分支并行规则

允许 Claude Code 建多个任务分支，但必须满足：

```text
一个分支只解决一个问题
每个分支能独立解释
每个分支能独立测试
每个分支能独立合并
```

推荐拆分方式：

```text
fix/copilot-migration-version
test/copilot-mcp-schema
ci/local-life-server-workflow-dispatch
docs/update-branch-workflow
```

不要把下面内容混在一个分支：

```text
migration 修复
CI workflow 修改
业务代码重构
文档大改
Agent evals 新增
```

如果任务变大，必须拆分分支。

## 当前项目推荐分支规划

近期可以建立这些分支：

```text
fix/copilot-migration-version
ci/local-life-server-workflow-dispatch
test/copilot-mcp-schema
test/copilot-agent-evals
docs/standardize-local-workflow
```

建议顺序：

1. fix/copilot-migration-version
   修复 Copilot migration V1 冲突。

2. ci/local-life-server-workflow-dispatch
   给 local-life-server CI 增加手动触发能力。

3. test/copilot-mcp-schema
   给 MCP tools/list 增加 schema 快照测试。

4. test/copilot-agent-evals
   建立 Copilot Agent evals 回归测试。

5. docs/standardize-local-workflow
   同步 README、CLAUDE.md、PROGRESS.md。

## main 保护原则

main 必须始终满足：

```text
能启动
能测试
能讲清楚
能作为面试展示版本
```

合并到 main 前必须满足：

```text
测试通过
diff 已审查
无 secrets
无 .env 泄漏
无无关文件
无大规模格式化噪音
无未解释的 migration
无未解释的 workflow 改动
```

## 自动化边界

Claude Code 可以自动做：

```text
创建任务分支
本地修改代码
本地运行测试
生成提交信息
生成 PR 标题和正文
创建 PR 前给用户确认
```

Claude Code 不可以自动做：

```text
直接 push main
自动 merge PR
自动部署
自动删除远程分支
自动丢弃未提交改动
自动改 secrets
自动改生产配置
自动绕过测试
```

## 任务开始固定输出

每次正式任务开始时输出：

```text
当前分支：
工作区状态：
任务类型：
建议分支名：
是否需要新建分支：
影响范围：
验证命令：
```

## 任务结束固定输出

每次正式任务结束时输出：

```text
当前分支：
完成内容：
修改文件：
提交信息建议：
验证命令：
验证结果：
是否建议创建 PR：
PR 标题：
PR 正文：
遗留风险：
```
