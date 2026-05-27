# Progress

## 2026-05-25
- 创建 `task_plan.md`、`findings.md`、`progress.md`
- 确认当前任务是双线项目规划与调研
- 已开始提炼本地规范并准备补充外部调研
- 已写入首批调研结论，确认双线项目都要围绕可深挖的工程问题展开
- 发现终端读取中文文件名存在编码问题，下一步改用程序枚举文件读取
- 已完成本地规范提炼，确认其核心是小步推进、即时验证、主链路复述、关键代码精读、文档复盘
- 已补充牛客项目求助帖与 Agent 面试帖的共性结论，开始收敛成双线项目设计
- 已新增 `双线项目路线.md`，完成统一业务域、双线分工、八周路线和简历骨架设计
- 当前规划阶段已完成，后续可直接进入第一阶段产物编写
- 已安装 `Temurin JDK 17.0.19+10`
- 已下载并配置 `Apache Maven 3.9.9`
- 已初始化 Maven 多模块工程、Maven Wrapper、双模块 Spring Boot 骨架
- 已新增环境文档、仓库结构文档、README、Compose 编排和环境变量模板
- `mvnw.cmd test` 已通过，说明工程骨架可构建
- `docker compose config` 已通过，说明 Compose 文件有效
- 当前唯一阻塞是 Docker daemon 未正常启动，容器暂未拉起
- 已确认 Ubuntu 原发行版 Appx 包缺失，WSL 运行时返回 `Wsl/CallMsi/ERROR_PATH_NOT_FOUND`
- 已将 Ubuntu 数据盘备份到 `D:\WSL\Ubuntu-22.04\ext4.vhdx`
- 已将原发行版注册项备份到 `D:\WSL\backups\ubuntu-22.04-lxss.reg`
- 已确认 `wsl --status` 与 `wsl -l -v` 恢复正常
- 已确认 `docker-desktop` WSL 发行版恢复正常，Docker Engine 可用
- 已启动并验证 `MySQL` 与 `Redis` 容器
- 已修复 `mysql:8.4` 过时启动参数导致的启动失败
- 已确认 Docker 内部 WSL 数据盘当前仍在 `C:\Users\86155\AppData\Local\Docker\wsl`
- 已初始化本地 git 仓库并创建首个提交 `bootstrap local life workspace`
- 已创建 GitHub 私有仓库 `fengting124/local-life-engineering-lab`
- 已将当前 `main` 分支推送到远程 `origin`
- 已完成根目录 Markdown 整理：项目文档、环境文档、过程文档、学习笔记、参考资料已分层归档到 `docs/`
- 已完成第一阶段第一个正式产物 `项目边界文档`
- 已完成第一阶段第二个正式产物 `领域模型文档`
- 已完成第一阶段第三个正式产物 `ER 图文档`
- 已完成第一阶段第四个正式产物 `核心时序图文档` 的登录链路，并为发笔记、抢券、下单支付预留结构
- 已将核心流程图调整为自上而下展示，并补全 `发笔记链路`
- 已补全 `抢券链路`，覆盖 Redis Lua 预扣、MQ 异步下单、幂等消费、失败补偿和面试深挖点
- 已补全 `下单支付链路`，覆盖订单与支付单边界、状态机、回调幂等、延时关单和对账补偿
- 已将主链路图改回 `sequenceDiagram` 保留组件泳道，订单状态机和支付单状态机继续保留 `flowchart TD`
- 已新增 `Copilot 企业级 Agent 设计`，将 AI 线升级为 Java MCP Server + Python LangGraph Agent + RAG + HITL + Evals 的企业级 Agent 方案
- 已新增 `技术选型文档`，同时覆盖 LocalLife 主项目和 LocalLife Copilot 的选型、替代方案、暂缓项和面试表达
- 已修补核心时序图、Copilot 设计和技术选型文档中的闭环问题：延时关单失败兜底、事务边界、短信防刷、抢券结果查询、MCP Schema、ReAct trace、模块化边界和模型选型
- 已新增 `项目增强路线图`，收敛 Canal、消息防丢、链路追踪、Streaming Tool Call、Prompt Injection、ShardingSphere、模型路由、Hybrid Search 等增强项的优先级和排期

## 2026-05-27
- 已完成第一阶段第八个正式产物 `接口规范文档`，覆盖统一响应结构、HTTP 状态码、业务错误码（18 个）、鉴权规范、分页规范、幂等规范、数据格式规范、URL 路径规范及核心接口清单（30 个接口）
- 每章节内嵌「为什么这么设计」，可直接用于面试口述
- 第一阶段所有规划产物已全部完成，可进入编码阶段
