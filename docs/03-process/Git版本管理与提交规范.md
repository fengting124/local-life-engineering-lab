# Git 版本管理与提交规范

> 目标：让每次提交都能回答「为什么改、改了什么、怎么验证、还有什么风险」。
> 本项目历史提交已经大量使用详细 commit body，本文件把这个习惯固化为项目规范。

最后更新：2026-06-09

---

## 一、分支命名

| 类型 | 格式 | 示例 |
|------|------|------|
| 功能 | `feat/<scope>-<summary>` | `feat/copilot-fast-path` |
| 修复 | `fix/<scope>-<summary>` | `fix/order-idempotency` |
| 测试/质量 | `test/<scope>-<summary>` | `test/ci-quality-gates` |
| 文档 | `docs/<summary>` | `docs/git-process` |
| 实验 | `spike/<summary>` | `spike/pact-provider` |

当前测试体系相关分支沿用 `test/...`，例如 `test/integration-load-testing`、
`test/ci-quality-gates`。同一主题下继续工作时优先从最近的测试分支拉新分支，不在 `main` 直接改。

---

## 二、提交信息格式

推荐使用 Conventional Commits 风格标题 + 结构化正文。

```text
test(ci): add quality gates and release smoke workflow

Goal:
- Close production-readiness gaps: missing CI, missing quality gates, missing cross-process smoke.

Changes:
- local-life-server: add JaCoCo core-class checks and PIT thresholds.
- copilot-agent-service: add pytest-cov and mutmut score gate.
- full stack: add release smoke workflow for server -> MCP -> agent.

Verification:
- mvn -pl local-life-server clean verify
- mvn -pl local-life-server test-compile org.pitest:pitest-maven:mutationCoverage
- DEBUG=false python -m pytest -q --cov --cov-fail-under=45
- DEBUG=false mutmut run

Risk / Follow-up:
- Full-stack smoke is a release/manual gate; keep PR CI focused and fast.
```

正文必须尽量写清：

- **Goal**：本次提交解决的目标或问题。
- **Changes**：按模块列出关键改动，不只写「update files」。
- **Verification**：实际跑过的命令和关键结果。
- **Risk / Follow-up**：没有完全解决的限制、需要真实环境验证的部分。

如果提交包含测试数据，正文里要写明 before/after 数字，例如测试数、覆盖率、变异分、压测并发量。

---

## 三、提交前检查

按改动范围选择最小但有效的检查：

| 改动范围 | 必跑命令 |
|----------|----------|
| `local-life-server` 核心逻辑 | `mvn -pl local-life-server test` |
| server 集成/DB/Redis/覆盖率门禁 | `mvn -pl local-life-server clean verify` |
| server 核心测试质量 | `mvn -pl local-life-server test-compile org.pitest:pitest-maven:mutationCoverage` |
| `copilot-agent-service` | `cd copilot-agent-service && DEBUG=false python -m pytest -q` |
| agent 覆盖率门禁 | `DEBUG=false python -m pytest -q --cov --cov-report=term-missing --cov-fail-under=45` |
| agent 变异测试 | `DEBUG=false mutmut run && python scripts/check_mutmut_score.py --min-kill-rate 50` |
| 全链路上线 smoke | `bash scripts/e2e-smoke.sh`（需三服务和依赖已启动） |

提交前同时运行：

```bash
git status --short
git diff --check
```

`git diff --check` 主要拦截尾随空格、冲突标记等低级问题。

---

## 四、CI / Release Gate

当前 GitHub Actions 分层如下：

| Workflow | 触发 | 门禁 |
|----------|------|------|
| `local-life-server CI` | PR / push main，server 路径 | `mvn clean verify` + JaCoCo core gate；PIT mutation gate |
| `copilot-agent-service CI` | PR / push main，agent 路径 | pytest-cov `--cov-fail-under=45` + mutmut kill rate `>=50%` |
| `local-life-copilot CI` | PR / push main，MCP 路径 | Java MCP Server 单测/切片/集成测试 |
| `full-stack release smoke` | `workflow_dispatch` / `v*` tag | Docker Compose 启三服务，跑 server -> MCP -> agent Fast Path smoke |

`full-stack release smoke` 不放进每个 PR 默认执行，因为它需要 Docker Compose 构建多服务和中间件，
耗时更长、环境变量更多；上线前或打 tag 时必须跑。

---

## 五、处理未提交改动

工作区可能存在别人或上一个工具留下的改动。继续工作时：

1. 先 `git status --short --branch` 看清范围。
2. 对会碰到的文件先读 diff，确认已有改动意图。
3. 不回滚无关改动；必要时把本次改动叠加在现有改动之上。
4. commit body 里写清哪些是本次延续完成的工作，哪些是前序未提交改动一起纳入。

这条规则尤其适用于 AI 协作：Claude/Codex/人工可能交替修改同一分支，commit 需要成为可靠的交接记录。
