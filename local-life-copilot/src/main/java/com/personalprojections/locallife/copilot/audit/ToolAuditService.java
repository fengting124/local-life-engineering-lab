package com.personalprojections.locallife.copilot.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }
}
