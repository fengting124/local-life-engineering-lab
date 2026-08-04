# 文档中心

- Status: Active
- Type: Overview
- Owners: Project maintainers
- Last verified: 2026-08-04
- Source of truth: `docs/文档规范.md`

本文档中心按文档类型和目录职责组织。第一次阅读项目时，从 [LocalLife 学习路线](./00-学习路线.md) 开始；维护文档时，先阅读 [文档规范](./文档规范.md) 和 [文档清单](./文档清单.md)。

## 入口

- [学习路线](./00-学习路线.md)：新读者从运行、业务、后端、Agent、测试和面试依次学习。
- [文档规范](./文档规范.md)：文档类型、生命周期、证据规则、写作规则和校验命令。
- [文档清单](./文档清单.md)：当前仓库 Markdown 文档的职责、状态和维护动作。
- [ADR 索引](./adr/README.md)：长期架构决策记录。

## 按类型导航

### Overview

- [项目 README](../README.md)
- [文档中心](./README.md)
- [项目文档索引](./01-project/00-文档索引.md)
- [ADR 索引](./adr/README.md)

### Tutorial

- [LocalLife 学习路线](./00-学习路线.md)
- [LocalLife Copilot 项目教程](./04-notes/LocalLifeCopilot项目教程.md)
- [LocalLife 项目接口教程](./04-notes/LocalLife项目接口教程.md)

### How-to

- [环境搭建](./02-environment/01-环境搭建.md)
- [WSL 本地开发指南](./02-environment/WSL本地开发指南.md)
- [Swagger 在线接口文档](./04-notes/Swagger在线接口文档.md)
- [企业级日志系统](./04-notes/企业级日志系统.md)
- [HITL 审批与安全恢复](./security/HITL审批与安全恢复.md)

### Reference

- [代码级实现地图](./01-project/02-代码级实现地图.md)
- [ER 图文档](./01-project/05-ER图文档.md)
- [接口规范文档](./01-project/10-接口规范文档.md)
- [测试总览与结果汇总](./04-notes/测试总览与结果汇总.md)
- [v0.1.0-rc 验收报告](./release/v0.1.0-rc验收报告.md)
- [Git 版本管理与提交规范](./03-process/Git版本管理与提交规范.md)

### Explanation

- [项目边界文档](./01-project/03-项目边界文档.md)
- [领域模型文档](./01-project/04-领域模型文档.md)
- [核心时序图文档](./01-project/06-核心时序图文档.md)
- [Copilot 企业级 Agent 设计](./01-project/07-Copilot企业级Agent设计.md)
- [后端与 Agent 链路架构图](./01-project/12-后端与Agent链路架构图.md)
- [技术选型文档](./01-project/08-技术选型文档.md)

### Plan

- [Agent Runtime 企业化落地计划](./01-project/09-AgentRuntime企业化落地计划.md)

### Interview

- [面试演示脚本](./05-interview/面试演示脚本.md)
- [后端 + Agent 面试高频题库](./05-interview/面试高频题库-后端Agent.md)
- [深度拷打与面试指南](./05-interview/深度拷打与面试指南.md)

## 目录职责

```text
docs/
├── README.md
├── 00-学习路线.md
├── 文档规范.md
├── 文档清单.md
├── 01-project/       项目边界、领域、接口、架构、数据与设计
├── 02-environment/   可重复执行的环境和部署操作指南
├── 03-process/       Git、协作、质量和维护流程
├── 04-notes/         深度教程和技术解释
├── 05-interview/     面试题库、演示脚本和表达材料
├── release/          阶段版本验收报告和发布证据入口
├── security/         安全机制、运维排障和事故处置
├── adr/              架构决策
├── templates/        文档模板
└── archive/          已替代或历史文档
```

## 文档维护命令

```bash
python scripts/check_docs.py
git diff --check
```

文档变更如果涉及 API、数据库、环境变量、Agent Graph、HITL、Checkpoint、RAG、权限、测试数字或部署方式，必须同步更新对应 Active 文档。
