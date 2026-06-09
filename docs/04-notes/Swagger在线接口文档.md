# Swagger 在线接口文档

本文记录 LocalLife 项目的在线 API 文档入口、使用方式和生产开放建议。

## 本地访问地址

启动开发环境后访问：

| 服务 | Swagger UI | OpenAPI JSON |
| --- | --- | --- |
| LocalLife Server | http://localhost:8080/swagger-ui.html | http://localhost:8080/v3/api-docs |
| LocalLife Copilot MCP Server | http://localhost:8081/swagger-ui.html | http://localhost:8081/v3/api-docs |
| Copilot Agent Service | http://localhost:8000/docs | http://localhost:8000/openapi.json |

如果启用了 Nginx 开发网关，当前网关只代理 Agent 文档：

| 服务 | 网关地址 |
| --- | --- |
| Copilot Agent Service | http://localhost/docs |

Java 两个服务的 Swagger 建议本地开发时直接访问 8080/8081。生产环境不建议把 MCP Server 文档暴露到公网。

## LocalLife Server 鉴权接口调用

LocalLife Server 的 Swagger UI 已配置 `bearer-jwt` 安全方案。

使用流程：

1. 先调用登录接口获取 token。
2. 点击 Swagger UI 右上角 `Authorize`。
3. 填入 token 原文即可，不需要手写 `Bearer ` 前缀。
4. 再调用需要登录的接口，Swagger 会自动添加 `Authorization: Bearer {token}`。

公开接口，如登录、门店查询、笔记详情、搜索、Actuator 和 Swagger 文档本身，不需要 token。

## Copilot MCP Server 调用

MCP Server 的核心入口是 `POST /mcp`，请求体使用 JSON-RPC 2.0。

本地调试示例：

```json
{
  "jsonrpc": "2.0",
  "id": "demo-1",
  "method": "tools/list",
  "params": {}
}
```

调用 `/mcp` 时需要带身份 Header：

```text
X-User-Id: 10001
X-User-Role: admin
```

如果角色是 `merchant`，还需要：

```text
X-Merchant-Id: 20001
```

这些 Header 在真实链路中由 Python Agent Service 注入，MCP Server 只信任内网调用。

## 生产环境建议

Swagger/OpenAPI 是开发和联调工具，不应无保护地暴露到公网。

推荐做法：

1. 开发环境默认开启。
2. 测试/预发环境放在 VPN、堡垒机或带登录的网关后。
3. 生产环境默认关闭，或仅允许内网 IP 访问。
4. MCP Server 文档不走公网网关，只保留内网访问。
5. 导出的 OpenAPI JSON 可以用于前端类型生成、接口契约评审和 Postman/Apifox 导入。
