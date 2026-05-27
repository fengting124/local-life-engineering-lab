# WSL 与 Docker 说明

## 1. 为什么要用 Docker

这个项目后续会依赖多种中间件：

- MySQL
- Redis
- Elasticsearch
- RocketMQ

如果全部直接装到 Windows 主机，会出现四类问题：

1. 版本污染
2. 环境回收困难
3. 切换配置成本高
4. 和真实部署环境差距大

Docker 的价值很直接：

- 每个中间件有独立镜像和独立文件系统
- 环境能按 `compose` 一键拉起和销毁
- 同一份编排文件可重复复用
- 更贴近后续 Linux 部署形态

## 2. MySQL、Redis 是跑在哪里

当前设计里，MySQL、Redis 都跑在你的本机上。  
更准确地说，它们跑在你这台机器上的 Docker 容器里。

这意味着：

- 服务物理位置是本机
- 服务进程位置是容器
- 数据默认保存在 Docker 管理的数据卷里

它们不是远程服务器，也不是直接作为 Windows 原生服务安装。

## 3. Windows 上的 Docker 和 WSL 是什么关系

在 Windows 上运行 Linux 容器，主流方案是 `Docker Desktop + WSL 2 backend`。

原因：

- 大多数后端中间件镜像都是 Linux 镜像
- WSL 2 提供 Linux 内核能力
- Docker Desktop 直接把 Linux 容器运行在 WSL 2 后端上

对当前项目，WSL 2 是最合适的路线。

## 4. 是否必须用 WSL

对当前这条开发路线，结论是：

- 如果你要跑 Linux 容器，优先用 `WSL 2`
- Hyper-V 也能跑一部分场景，但对个人开发环境和日常 Java 后端项目，`WSL 2` 更主流

## 5. 当前机器的真实状态

### 已确认

- `Docker` 客户端已安装
- `Docker Compose` 已安装
- `Docker Desktop` 可启动进程
- `Ubuntu-22.04` 的数据盘原本在 `C:\Users\86155\AppData\Local\Packages\...\LocalState\ext4.vhdx`

### 已发现问题

- `Get-AppxPackage *Ubuntu*` 没有返回 Ubuntu 发行版应用包
- `wsl --status` 与 `wsl -l -v` 都失败
- WSL 运行时的明确报错是：`Wsl/CallMsi/ERROR_PATH_NOT_FOUND`
- Docker daemon 无法正常启动，根因大概率就在 WSL 运行时损坏

### 已完成保护动作

- 已把 Ubuntu 的 `ext4.vhdx` 复制到 `D:\WSL\Ubuntu-22.04\ext4.vhdx`
- 已备份原有发行版注册项到 `D:\WSL\backups\ubuntu-22.04-lxss.reg`

## 6. 当前结论

当前不是 MySQL、Redis 或 Compose 文件有问题。  
当前是 `WSL 运行时本体异常`，导致 Docker 的 Linux 后端无法起来。

## 7. D 盘策略

后续统一按下面的策略推进：

- WSL 发行版数据盘放到 `D:\WSL\`
- Docker 的 WSL 数据根放到 `D:\DockerDesktop\wsl`
- 项目代码继续放在 `D:\Personal_Projections`

## 8. 当前状态

WSL 运行时已经恢复。  
当前状态如下：

1. `Ubuntu-22.04` 已正常运行在 `WSL 2`
2. `docker-desktop` 已正常运行在 `WSL 2`
3. `Docker Engine` 已可用
4. `MySQL` 和 `Redis` 已在本机容器中成功启动并通过健康检查

## 9. 仍需注意的点

当前 Docker 的内部 WSL 数据盘仍在 `C:`：

- `C:\Users\86155\AppData\Local\Docker\wsl\disk\docker_data.vhdx`
- `C:\Users\86155\AppData\Local\Docker\wsl\main\ext4.vhdx`

这说明：

- Ubuntu 发行版已经在 `D:`
- Docker 自己的内部数据盘还没有迁走

## 10. 下一步

当前环境已经足够进入项目开发。  
开发顺序继续按这条线推进：

1. 保持 `MySQL`、`Redis` 作为本地基础设施
2. 开始写项目边界文档
3. 开始画领域模型和 ER 图
4. 再进入后端主项目编码
