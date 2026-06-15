package com.personalprojections.locallife.copilot.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 工具调用审计服务。
 *
 * <p>所有 MCP 工具调用结束后（无论成功或失败），由 McpController 调用此服务记录审计日志。
 *
 * <h2>为什么用异步写</h2>
 * <p>审计写入不应阻塞工具调用的响应链路：
 * <ul>
 *   <li>工具调用 → 返回结果给 Agent（同步，需要低延迟）</li>
 *   <li>审计记录 → 写数据库（异步，可以接受毫秒级延迟）</li>
 * </ul>
 * 使用 {@code @Async} 让审计写入在独立线程池执行，不影响 P99 延迟。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolAuditService {

    private final ToolAuditMapper toolAuditMapper;
    private final ObjectMapper objectMapper;

    /**
     * 异步记录工具调用成功日志。
     *
     * @param sessionId  会话 ID
     * @param threadId   LangGraph thread ID
     * @param toolName   工具名称
     * @param input      入参对象
     * @param output     出参对象
     * @param durationMs 耗时（毫秒）
     */
    @Async
    public void recordSuccess(Long sessionId, String threadId, String toolName,
                              Object input, Object output, long durationMs) {
        RbacContext ctx = RbacContext.get();
        record(sessionId, threadId, toolName, input, output, (int) durationMs,
                "success", null, ctx);
    }

    /**
     * 异步记录工具调用失败日志。
     *
     * @param sessionId  会话 ID
     * @param threadId   LangGraph thread ID
     * @param toolName   工具名称
     * @param input      入参对象
     * @param errorMsg   错误信息
     * @param durationMs 耗时（毫秒）
     */
    @Async
    public void recordError(Long sessionId, String threadId, String toolName,
                            Object input, String errorMsg, long durationMs) {
        RbacContext ctx = RbacContext.get();
        record(sessionId, threadId, toolName, input, null, (int) durationMs,
                "error", errorMsg, ctx);
    }

    private void record(Long sessionId, String threadId, String toolName,
                        Object input, Object output, int durationMs,
                        String status, String errorMsg, RbacContext ctx) {
        try {
            ToolAuditLog log = ToolAuditLog.builder()
                    .sessionId(sessionId)
                    .threadId(threadId)
                    .toolName(toolName)
                    .toolInput(toJson(input))
                    .toolOutput(toJson(output))
                    .durationMs(durationMs)
                    .status(status)
                    .errorMsg(errorMsg)
                    .userId(ctx != null ? ctx.getUserId() : null)
                    .userRole(ctx != null ? ctx.getRole() : null)
                    .createdAt(LocalDateTime.now())
                    .build();
            toolAuditMapper.insert(log);
        } catch (Exception e) {
            // 审计失败不影响业务主链路，仅 WARN 日志
            log.warn("[ToolAudit] 审计日志写入失败: tool={}, error={}", toolName, e.getMessage());
        }
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String raw && isValidJson(raw)) {
            // 已经是合法 JSON 文本（如工具直接透传上游返回的原始 JSON）——原样存，不重复编码
            return raw;
        }
        // 其余情况——含"长得像字符串、其实不是合法 JSON"的裸字符串——统一走正常序列化：
        // Jackson 会把 String 序列化成带引号转义的 JSON 字符串字面量，天然合法
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // tool_input/tool_output 是 JSON NOT NULL 列：序列化失败时绝不能像旧实现那样
            // 直接 return obj.toString()——那同样不保证是合法 JSON，会在落库这一关报错。
            // 退化成一个保证合法的 JSON 字符串字面量，把原始内容连同失败原因一并留痕。
            log.warn("[ToolAudit] 序列化为 JSON 失败，退化为字符串字面量: type={}, error={}",
                    obj.getClass().getSimpleName(), e.getMessage());
            return TextNode.valueOf(String.valueOf(obj)).toString();
        }
    }

    private boolean isValidJson(String text) {
        try {
            objectMapper.readTree(text);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
