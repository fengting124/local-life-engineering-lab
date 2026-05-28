package com.personalprojections.locallife.copilot.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工具调用审计日志实体（对应 tool_audit_log 表）。
 *
 * <p>每次 MCP 工具调用都记录一条审计日志，三个核心用途：
 * <ol>
 *   <li>问题排查：入参 + 出参完整保存，方便复盘工具执行过程</li>
 *   <li>Evals 数据：工具调用序列是评测「工具调用准确率」的原始数据</li>
 *   <li>告警监控：按 tool_name + created_at 统计错误率，发现异常工具</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tool_audit_log")
public class ToolAuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;
    private String threadId;
    private String traceId;

    /** 工具名称（如 query_order / execute_refund） */
    private String toolName;

    /** 工具入参（JSON 字符串，完整保存，方便复盘） */
    private String toolInput;

    /** 工具出参（JSON 字符串，成功时为结果，失败时为错误） */
    private String toolOutput;

    /** 执行耗时（毫秒） */
    private Integer durationMs;

    /** 执行状态：success / error / timeout */
    private String status;

    /** 错误信息（status=error 时填写） */
    private String errorMsg;

    private Long userId;
    private String userRole;

    private LocalDateTime createdAt;
}
