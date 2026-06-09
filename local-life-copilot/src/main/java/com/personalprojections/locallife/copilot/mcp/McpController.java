package com.personalprojections.locallife.copilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.audit.ToolAuditService;
import com.personalprojections.locallife.copilot.mcp.dto.McpError;
import com.personalprojections.locallife.copilot.mcp.dto.McpRequest;
import com.personalprojections.locallife.copilot.mcp.dto.McpResponse;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool;
import com.personalprojections.locallife.copilot.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 协议主入口 Controller（POST /mcp）。
 *
 * <p>实现 JSON-RPC 2.0 over HTTP 的 MCP Server 端点。
 * Python Agent 通过 HTTP POST 发送 JSON-RPC 请求，此 Controller 负责路由到对应方法。
 *
 * <h2>支持的方法</h2>
 * <ul>
 *   <li>{@code initialize}  — 握手，返回服务能力声明</li>
 *   <li>{@code tools/list}  — 返回所有工具定义列表（Agent 用于构建 System Prompt）</li>
 *   <li>{@code tools/call}  — 执行指定工具调用（核心功能）</li>
 * </ul>
 *
 * <h2>tools/call 执行流程</h2>
 * <pre>
 *   1. 解析 params.name + params.arguments
 *   2. 在 ToolRegistry 中查找工具
 *   3. RBAC 权限校验（工具 x-allowed-roles vs 当前 RbacContext.role）
 *   4. 记录开始时间
 *   5. 调用 tool.execute(arguments)
 *   6. 捕获工具异常并转换为对应 MCP 错误类型
 *   7. 异步记录审计日志（ToolAuditService）
 *   8. 返回 MCP result（content[0].type=text）
 * </pre>
 *
 * <h2>Session / Thread ID 约定</h2>
 * <p>Python Agent 在 Header 中传入：
 * <pre>
 *   X-Session-Id: 123456
 *   X-Thread-Id: thread-abc-001
 * </pre>
 * MCP Server 将这两个字段写入审计日志，不做业务处理。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpController {

    private final ToolRegistry toolRegistry;
    private final ToolAuditService auditService;
    private final ObjectMapper objectMapper;
    private final com.personalprojections.locallife.copilot.ratelimit.ToolRateLimiter rateLimiter;

    @Value("${mcp.server.name:locallife-mcp-server}")
    private String serverName;

    @Value("${mcp.server.version:1.0.0}")
    private String serverVersion;

    /**
     * MCP 协议唯一入口（JSON-RPC 2.0 over HTTP）。
     *
     * @param request   JSON-RPC 请求体
     * @param sessionId 会话 ID（来自 X-Session-Id Header，用于审计）
     * @param threadId  LangGraph thread ID（来自 X-Thread-Id Header，用于审计）
     * @return JSON-RPC 响应体
     */
    @PostMapping("/mcp")
    public McpResponse handle(
            @RequestBody McpRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) Long sessionId,
            @RequestHeader(value = "X-Thread-Id", required = false) String threadId) {

        String id = request.getId();
        String method = request.getMethod();

        log.debug("[MCP] 收到请求: method={}, id={}, sessionId={}", method, id, sessionId);

        return switch (method) {
            case "initialize"  -> handleInitialize(id);
            case "tools/list"  -> handleToolsList(id);
            case "tools/call"  -> handleToolsCall(id, request.getParams(), sessionId, threadId);
            default -> McpResponse.error(id,
                    McpError.builder()
                            .code(-32601 /* JSON-RPC Method not found */)
                            .message("method_not_found")
                            .data(Map.of("method", method, "hint", "支持：initialize / tools/list / tools/call"))
                            .build());
        };
    }

    // =========================================================
    // initialize —— MCP 握手
    // =========================================================

    /**
     * 返回 MCP Server 的能力声明。
     * Python Agent 在建立连接时调用此方法，确认服务端支持哪些协议版本和功能。
     */
    private McpResponse handleInitialize(String id) {
        Map<String, Object> result = Map.of(
                "protocolVersion", "2024-11-05",  // MCP 协议版本
                "serverInfo", Map.of(
                        "name", serverName,
                        "version", serverVersion
                ),
                "capabilities", Map.of(
                        "tools", Map.of()   // 声明支持 tools 能力
                )
        );
        return McpResponse.success(id, result);
    }

    // =========================================================
    // tools/list —— 返回工具列表
    // =========================================================

    /**
     * 返回所有已注册工具的定义列表。
     * Agent 在启动时调用此接口，将工具 Schema 嵌入 LLM System Prompt。
     */
    private McpResponse handleToolsList(String id) {
        List<ToolDefinition> definitions = toolRegistry.listDefinitions();
        Map<String, Object> result = Map.of("tools", definitions);
        return McpResponse.success(id, result);
    }

    // =========================================================
    // tools/call —— 执行工具调用（核心）
    // =========================================================

    /**
     * 执行工具调用。
     *
     * <p>这是 MCP Server 的核心功能：Agent 决定调用哪个工具后，
     * 通过此接口传入工具名和参数，MCP Server 执行并返回结构化结果。
     */
    private McpResponse handleToolsCall(String id, JsonNode params,
                                        Long sessionId, String threadId) {
        // ---- 解析参数 ----
        if (params == null || !params.has("name")) {
            return McpResponse.error(id, McpError.parameterError("params.name 不能为空", "格式：{\"name\":\"query_order\",\"arguments\":{...}}"));
        }

        String toolName  = params.get("name").asText();
        JsonNode args    = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();

        // ---- 查找工具 ----
        Optional<McpTool> toolOpt = toolRegistry.find(toolName);
        if (toolOpt.isEmpty()) {
            return McpResponse.error(id,
                    McpError.builder()
                            .code(-32601 /* JSON-RPC Method not found */)
                            .message("tool_not_found")
                            .data(Map.of("tool", toolName, "hint", "调用 tools/list 查看可用工具"))
                            .build());
        }
        McpTool tool = toolOpt.get();

        // ---- 工具级别限流（在 RBAC 之后，防止非法请求消耗配额）----
        if (!rateLimiter.isAllowed(toolName)) {
            return McpResponse.error(id, McpError.builder()
                    .code(-32603)
                    .message("tool_timeout")
                    .data(Map.of(
                            "reason", "tool_rate_limited",
                            "detail", "工具 " + toolName + " 调用过于频繁，请稍后再试（限制：每分钟 100 次/工具）",
                            "hint",   "如果是 Agent 循环调用，请检查终止条件"))
                    .build());
        }

        // ---- RBAC 权限校验 ----
        RbacContext ctx = RbacContext.get();
        List<String> allowedRoles = tool.getDefinition().getXAllowedRoles();
        if (ctx == null || (allowedRoles != null && !allowedRoles.contains(ctx.getRole()))) {
            String role = ctx != null ? ctx.getRole() : "unknown";
            log.warn("[MCP] RBAC 拒绝: tool={}, role={}", toolName, role);
            return McpResponse.error(id, McpError.permissionDenied(
                    "角色 " + role + " 无权调用工具 " + toolName + "，允许角色：" + allowedRoles));
        }

        // ---- 执行工具 ----
        long startMs = System.currentTimeMillis();
        try {
            Object result = tool.execute(args);
            long costMs = System.currentTimeMillis() - startMs;

            log.debug("[MCP] 工具调用成功: tool={}, costMs={}", toolName, costMs);
            auditService.recordSuccess(sessionId, threadId, toolName, args, result, costMs);

            // MCP content 格式：content 数组，每个元素为 {type, text}
            return buildContentResult(id, result);

        } catch (McpTool.ToolParameterException e) {
            long costMs = System.currentTimeMillis() - startMs;
            auditService.recordError(sessionId, threadId, toolName, args, e.getMessage(), costMs);
            return McpResponse.error(id, McpError.parameterError(e.getMessage(), e.getHint()));

        } catch (McpTool.ToolPermissionException e) {
            long costMs = System.currentTimeMillis() - startMs;
            auditService.recordError(sessionId, threadId, toolName, args, e.getMessage(), costMs);
            return McpResponse.error(id, McpError.permissionDenied(e.getMessage()));

        } catch (McpTool.ToolNotFoundException e) {
            long costMs = System.currentTimeMillis() - startMs;
            auditService.recordError(sessionId, threadId, toolName, args, e.getMessage(), costMs);
            return McpResponse.error(id, McpError.notFound(e.getMessage()));

        } catch (McpTool.ToolBusinessException e) {
            long costMs = System.currentTimeMillis() - startMs;
            auditService.recordError(sessionId, threadId, toolName, args, e.getMessage(), costMs);
            return McpResponse.error(id, McpError.businessConflict(e.getMessage()));

        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startMs;
            log.error("[MCP] 工具调用异常: tool={}, error={}", toolName, e.getMessage(), e);
            auditService.recordError(sessionId, threadId, toolName, args, e.getMessage(), costMs);
            return McpResponse.error(id, McpError.internalError(e.getMessage()));
        }
    }

    /**
     * 将工具执行结果包装为 MCP content 格式，返回完整的 {@link McpResponse}。
     *
     * <p>MCP 规范要求 result 为 {@code {"content": [{"type": "text", "text": "..."}]}}。
     * Agent 解析 content[0].text 获取工具返回值。
     *
     * <h2>序列化策略</h2>
     * <ul>
     *   <li>{@code String} 结果直接放入 text，不做额外 JSON 编码</li>
     *   <li>其余类型（{@code Map}、{@code JsonNode}、DTO、{@code List}、{@code null} 等）
     *       统一走 {@link com.fasterxml.jackson.databind.ObjectMapper#writeValueAsString}，
     *       序列化后再用 {@code readTree} 复核一遍，确保 text 字段是合法 JSON</li>
     *   <li>任何序列化失败（如 {@code FAIL_ON_EMPTY_BEANS}、循环引用等）
     *       直接返回标准 MCP internal_error，不做 {@code .toString()} 兜底——
     *       兜底产出不保证合法 JSON，会让 Agent 端解析炸掉</li>
     * </ul>
     */
    private McpResponse buildContentResult(String id, Object toolResult) {
        String text;
        if (toolResult instanceof String s) {
            text = s;
        } else {
            try {
                text = objectMapper.writeValueAsString(toolResult);
                objectMapper.readTree(text);  // defensive: verify output is valid JSON
            } catch (Exception e) {
                log.warn("[MCP] 工具返回值序列化失败: type={}, error={}",
                        toolResult == null ? "null" : toolResult.getClass().getSimpleName(),
                        e.getMessage());
                return McpResponse.error(id, McpError.internalError("工具返回值序列化失败：" + e.getMessage()));
            }
        }
        ArrayNode content = objectMapper.createArrayNode();
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "text");
        item.put("text", text);
        content.add(item);
        return McpResponse.success(id, Map.of("content", content));
    }
}
