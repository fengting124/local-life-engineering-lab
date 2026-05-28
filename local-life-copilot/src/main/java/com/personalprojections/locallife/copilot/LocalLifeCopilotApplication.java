package com.personalprojections.locallife.copilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * LocalLife Copilot MCP Server 启动类。
 *
 * <h2>职责</h2>
 * <p>本服务是 LocalLife Copilot 架构中的 Java MCP Server 层：
 * <ul>
 *   <li>对外：暴露 {@code POST /mcp} 端点，实现 MCP JSON-RPC 2.0 协议</li>
 *   <li>对内：通过 ToolRegistry 管理所有业务工具（query_order / execute_refund 等）</li>
 *   <li>安全：通过 RbacFilter 强制注入身份上下文，工具执行前做权限校验</li>
 *   <li>审计：所有工具调用异步写入 tool_audit_log 表</li>
 * </ul>
 *
 * <h2>启动顺序</h2>
 * <pre>
 *   1. 先启动 LocalLife Server（端口 8080）—— MCP 工具需要访问同一个 MySQL
 *   2. 再启动本服务（端口 8081）
 *   3. 最后启动 Python Agent Service（端口 8000）—— 依赖 MCP Server 提供工具
 * </pre>
 *
 * <h2>架构位置</h2>
 * <pre>
 *   Chat UI
 *      ↓ HTTP + SSE
 *   copilot-agent-service (Python, :8000)
 *      ↓ MCP over HTTP
 *   local-life-copilot MCP Server (Java, :8081)  ← 本服务
 *      ↓ JDBC
 *   MySQL（local_life 数据库，与 LocalLife Server 共享）
 * </pre>
 */
@EnableAsync               // 启用异步（ToolAuditService 异步写审计日志）
@MapperScan({
        "com.personalprojections.locallife.copilot.audit",          // 工具审计 Mapper
        "com.personalprojections.locallife.copilot.domain.mapper"   // 业务只读 Mapper
})
@SpringBootApplication
public class LocalLifeCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalLifeCopilotApplication.class, args);
    }
}
