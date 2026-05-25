# 双线项目规划任务

## Goal
基于《独立项目ai辅助编程规范》的范式，规划一条传统后端主项目线和一条 AI 应用项目线，要求能支撑简历表达、面试深挖和系统能力提升。

## Phases
| Phase | Status | Description |
|-------|--------|-------------|
| 1 | complete | 建立计划文件并整理本地规范范式 |
| 2 | complete | 调研外部信息，归纳项目选题与面试关注点 |
| 3 | complete | 设计双线项目方案、技术栈、模块和阶段目标 |
| 4 | complete | 输出第一阶段实施方案与文档沉淀规则 |

## Key Constraints
- 方案必须围绕工程问题和技术决策展开
- 传统后端项目要覆盖缓存、MQ、搜索、压测、补偿等深水区
- AI 项目要覆盖 Agent、RAG、工具调度、评测、流式输出、MCP
- 输出要能直接映射到简历描述和面试追问

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| 本地文档在终端输出出现编码错乱 | 1 | 改用显式 UTF-8 方式读取 |
| Docker daemon 未启动，`com.docker.service` 处于停止状态 | 1 | 已确认不是 compose 文件语法问题，先记录为环境阻塞 |
