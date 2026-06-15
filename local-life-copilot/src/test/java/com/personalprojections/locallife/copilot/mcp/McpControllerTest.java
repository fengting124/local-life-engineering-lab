package com.personalprojections.locallife.copilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.copilot.audit.ToolAuditService;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import com.personalprojections.locallife.copilot.ratelimit.ToolRateLimiter;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool;
import com.personalprojections.locallife.copilot.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Data;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link McpController} 切片测试。
 *
 * <h2>覆盖范围</h2>
 * <ul>
 *   <li>JSON-RPC 方法分发：initialize / tools/list / 未知 method</li>
 *   <li>tools/call 管道的精确执行顺序：参数校验 → 工具查找 → <b>限流 → RBAC</b> → 执行
 *       （顺序本身是不变量：用一个"两层都会拒绝"的场景证明限流确实排在 RBAC 之前）</li>
 *   <li>4 种 typed exception + 通用异常到 {@link com.personalprojections.locallife.copilot.mcp.dto.McpError}
 *       的映射，以及"成功 / 失败两条路径都必须触发审计"这一约束</li>
 *   <li>{@code buildContentResult} 对工具结果的包装（String 直通 / 对象 JSON 序列化）</li>
 * </ul>
 *
 * <h2>关于 RbacFilter：真实运行，不用替身</h2>
 * <p>{@code @WebMvcTest} 会把 {@link com.personalprojections.locallife.copilot.rbac.RbacFilter}
 * 这个 {@code @Component implements Filter} 一并装进切片上下文（与 local-life-server 的
 * {@code AuthControllerTest} 踩过的坑同源）。但和那边需要用 {@code @MockitoBean} 替身
 * 拦截器不同——{@code RbacFilter} 不查 Redis/DB、没有任何构造依赖，让它真实运行、
 * 按场景传递 {@code X-User-Id/X-User-Role/X-Merchant-Id} 反而比搭一套"伪造 RbacContext"
 * 的替身基础设施更简单，也更贴近 Agent Service 真实打过来的请求形态。
 * {@link com.personalprojections.locallife.copilot.rbac.RbacFilterTest} 已经独立、
 * 详尽地覆盖过过滤器自身的行为，这里只需要为每个场景选对角色，
 * 不重复验证"过滤器本身对不对"。
 *
 * <h2>一处特意不修的命名不一致（按现状断言，仅记录）</h2>
 * <p>限流触发时返回的 {@code McpError} 里，{@code code}/{@code message} 是
 * {@code -32603}/{@code "tool_timeout"}，但 {@code data.reason} 却写的是
 * {@code "tool_rate_limited"}——同一个错误体内"语义标签"和"对外消息"对不上号，
 * 容易让 Agent 端按 {@code message} 分支时走错重试策略。但这是 Java MCP Server
 * 与 Python Agent Service 之间已经在用的线上契约字段，贸然"改对"会是一次跨服务的
 * breaking change，超出"补测试"的范围——下面的用例按<b>当前真实行为</b>断言，
 * 把这个不一致钉成一个看得见、有名字的回归点，而不是顺手"修掉"。
 */
@WebMvcTest(McpController.class)
class McpControllerTest {

    private static final String MCP_URL = "/mcp";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ToolRegistry toolRegistry;

    @MockitoBean
    private ToolRateLimiter rateLimiter;

    @MockitoBean
    private ToolAuditService auditService;

    // 第二个"意外住户"（第一个是 RbacFilter，已在类注释里说明为何让它真实运行）：
    // LocalLifeCopilotApplication 上的 @MapperScan({"...audit", "...domain.mapper"})
    // 会把 ToolAuditMapper 等接口注册成 MapperFactoryBean——这是 @Import 级别的注册，
    // 不受 @WebMvcTest 切片过滤器影响，容器刷新时立即被实例化并执行 checkDaoConfig()，
    // 要求存在 SqlSessionFactory/SqlSessionTemplate（而切片上下文不会自动配置 MyBatis，
    // 两者都没有，实测会在 ApplicationContext 启动阶段直接抛 BeanCreationException）。
    // 这与 local-life-server 的 AuthControllerTest 踩的是同一个坑、用的是同一种解法：
    // RETURNS_DEEP_STUBS 让 mock.getConfiguration() 返回深层 stub 而不是 null，
    // 使 checkDaoConfig 内部的 hasMapper()/addMapper() 都能安全空跑过去，不碰真实 SQL。
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private SqlSessionTemplate sqlSessionTemplate;

    @BeforeEach
    void allowAllByRateLimiterByDefault() {
        // 大多数用例只关心 RBAC / 工具执行本身；限流默认放行，
        // 限流触发的场景由专门用例覆盖并显式 stub 成 false（覆盖这条默认 stub）。
        when(rateLimiter.isAllowed(anyString())).thenReturn(true);
    }

    @AfterEach
    void clearRbacContext() {
        // RbacFilter 自身的 finally 块已经会清理（RbacFilterTest 已验证过这个保证）；
        // 这里多一道保险，防止任何一条用例以非预期方式绕过过滤器链而污染下一条用例的 ThreadLocal。
        RbacContext.clear();
    }

    /** 构造一条会真实经过 RbacFilter 的 /mcp 请求：按角色补齐过滤器要求的身份 Header。 */
    private MockHttpServletRequestBuilder mcpRequest(String jsonBody, String role) {
        MockHttpServletRequestBuilder builder = post(MCP_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", "10001")
                .header("X-User-Role", role)
                .content(jsonBody);
        return "merchant".equals(role) ? builder.header("X-Merchant-Id", "20001") : builder;
    }

    /** 构造一个"已注册到 ToolRegistry"的工具替身：调用方按需控制其 xAllowedRoles 与 execute() 行为。 */
    private McpTool stubRegisteredTool(String name, List<String> allowedRoles) {
        McpTool tool = mock(McpTool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDefinition()).thenReturn(ToolDefinition.builder().name(name).xAllowedRoles(allowedRoles).build());
        when(toolRegistry.find(name)).thenReturn(Optional.of(tool));
        return tool;
    }

    // =====================================================================
    // 1. JSON-RPC 方法分发：initialize / tools/list / 未知 method
    // =====================================================================

    @Test
    void initialize_returnsProtocolVersionAndServerInfo() throws Exception {
        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-1","method":"initialize","params":{}}
                """, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("req-1"))
                .andExpect(jsonPath("$.result.protocolVersion").value("2024-11-05"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("locallife-mcp-server"))
                .andExpect(jsonPath("$.result.serverInfo.version").value("1.0.0"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists());
    }

    @Test
    void toolsList_returnsDefinitionsFromRegistry() throws Exception {
        ToolDefinition def = ToolDefinition.builder()
                .name("query_order")
                .description("查询订单详情")
                .xAllowedRoles(List.of("merchant", "cs", "admin"))
                .build();
        when(toolRegistry.listDefinitions()).thenReturn(List.of(def));

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-2","method":"tools/list","params":{}}
                """, "cs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[0].name").value("query_order"))
                .andExpect(jsonPath("$.result.tools[0].description").value("查询订单详情"));
    }

    @Test
    void unknownMethod_returnsMethodNotFound_withMethodNameAndHintInData() throws Exception {
        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-3","method":"tools/explode","params":{}}
                """, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601))
                .andExpect(jsonPath("$.error.message").value("method_not_found"))
                .andExpect(jsonPath("$.error.data.method").value("tools/explode"))
                .andExpect(jsonPath("$.error.data.hint").value("支持：initialize / tools/list / tools/call"));
    }

    // =====================================================================
    // 2. tools/call —— 入口校验：缺 params.name / 工具未注册
    // =====================================================================

    @Test
    void toolsCall_missingParamsName_returnsParameterError() throws Exception {
        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-4","method":"tools/call","params":{"arguments":{}}}
                """, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("parameter_error"))
                .andExpect(jsonPath("$.error.data.reason").value("parameter_error"))
                .andExpect(jsonPath("$.error.data.detail").value("params.name 不能为空"))
                .andExpect(jsonPath("$.error.data.hint").value("格式：{\"name\":\"query_order\",\"arguments\":{...}}"));
    }

    @Test
    void toolsCall_toolNotRegistered_returnsToolNotFound_withPlainMapData_notStructuredError() throws Exception {
        when(toolRegistry.find("does_not_exist")).thenReturn(Optional.empty());

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-5","method":"tools/call","params":{"name":"does_not_exist","arguments":{}}}
                """, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601))
                .andExpect(jsonPath("$.error.message").value("tool_not_found"))
                // 这条错误是控制器内联拼的 Map.of("tool", ..., "hint", ...)，
                // 不是其余错误统一走的 StructuredError{reason,detail,hint} —— 形状特意不同，
                // 在断言里把这个差异钉死，防止日后有人"顺手"把它们对齐成一个形状、
                // 反而让已经按旧形状解析的 Agent 端 parsing 炸掉。
                .andExpect(jsonPath("$.error.data.tool").value("does_not_exist"))
                .andExpect(jsonPath("$.error.data.hint").value("调用 tools/list 查看可用工具"))
                .andExpect(jsonPath("$.error.data.reason").doesNotExist())
                .andExpect(jsonPath("$.error.data.detail").doesNotExist());

        verify(auditService, never()).recordSuccess(any(), any(), any(), any(), any(), anyLong());
        verify(auditService, never()).recordError(any(), any(), any(), any(), any(), anyLong());
    }

    // =====================================================================
    // 3. tools/call —— 管道顺序的关键不变量：限流先于 RBAC
    // =====================================================================

    @Test
    void toolsCall_rateLimitWins_evenInAScenarioWhereRbacWouldAlsoReject() throws Exception {
        // 刻意构造"两层校验都会拒绝"的场景：merchant 角色调用只允许 cs/admin 的工具
        // （RBAC 单独看必拒），同时把限流也 stub 成触发。
        //
        // 如果实现是"先 RBAC 后限流"，这里会先因为角色不对返回 permission_denied，
        // 限流的 stub 永远不会被读到；只有先检查限流，返回的才会是 tool_rate_limited——
        // 这正对应控制器里"避免非法/恶意请求消耗调用配额"的顺序设计。用这种
        // "双重否决，看谁先开枪"的构造方式，把隐含的执行顺序变成可执行的回归保护。
        stubRegisteredTool("execute_refund", List.of("cs", "admin"));
        when(rateLimiter.isAllowed("execute_refund")).thenReturn(false);

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-6","method":"tools/call","params":{"name":"execute_refund","arguments":{}}}
                """, "merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32603))
                .andExpect(jsonPath("$.error.message").value("tool_timeout"))
                // 见类注释：code/message 说的是 tool_timeout，但 data.reason 却是
                // tool_rate_limited —— 现状如此，按现状断言，不在写测试时顺手"修对"。
                .andExpect(jsonPath("$.error.data.reason").value("tool_rate_limited"))
                .andExpect(jsonPath("$.error.data.detail")
                        .value("工具 execute_refund 调用过于频繁，请稍后再试（限制：每分钟 100 次/工具）"))
                .andExpect(jsonPath("$.error.data.hint").value("如果是 Agent 循环调用，请检查终止条件"));

        verify(auditService, never()).recordSuccess(any(), any(), any(), any(), any(), anyLong());
        verify(auditService, never()).recordError(any(), any(), any(), any(), any(), anyLong());
    }

    // =====================================================================
    // 4. tools/call —— RBAC 拒绝（角色不在 xAllowedRoles 中）
    // =====================================================================

    @Test
    void toolsCall_roleNotAllowed_returnsPermissionDenied_andNeverReachesExecuteOrAudit() throws Exception {
        stubRegisteredTool("execute_refund", List.of("cs", "admin"));

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-7","method":"tools/call","params":{"name":"execute_refund","arguments":{}}}
                """, "merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("permission_denied"))
                .andExpect(jsonPath("$.error.data.detail")
                        .value("角色 merchant 无权调用工具 execute_refund，允许角色：[cs, admin]"));

        // RBAC 拒绝在 try/execute 之前短路返回——既不会进 success 分支，也不会落进 catch
        // 块触发 recordError。换句话说："被拒绝调用的尝试"当前不会进入 tool_audit_log，
        // 和"工具不存在"是同一类行为。记在这里作为观察点：如果以后要做"调用尝试"维度的
        // 安全审计（而不仅是"成功执行了什么"），这会是一个需要重新设计的边界，
        // 但显然不是写测试时顺手能改的范围。
        verify(auditService, never()).recordSuccess(any(), any(), any(), any(), any(), anyLong());
        verify(auditService, never()).recordError(any(), any(), any(), any(), any(), anyLong());
    }

    // =====================================================================
    // 5. tools/call —— 成功路径：buildContentResult 包装 + 审计成功记录
    // =====================================================================

    @Test
    void toolsCall_success_wrapsObjectResultAsJsonText_andRecordsAuditSuccessWithRawResult() throws Exception {
        Map<String, Object> rawResult = Map.of("order_status", "PAID", "amount", 2990);
        McpTool tool = stubRegisteredTool("query_order", List.of("merchant", "cs", "admin"));
        when(tool.execute(any())).thenReturn(rawResult);

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-8","method":"tools/call","params":{"name":"query_order","arguments":{"order_id":"ORDER_1"}}}
                """, "merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                // Map 不是 String，buildContentResult 应该把它序列化成 JSON 文本再包装——
                // 用子串断言而非整串相等，因为 Map.of(...) 的键序不保证稳定。
                .andExpect(jsonPath("$.result.content[0].text", containsString("\"order_status\":\"PAID\"")))
                .andExpect(jsonPath("$.result.content[0].text", containsString("\"amount\":2990")));

        // 关键不变量：写入审计的是工具返回的"原始结果对象" rawResult 本身，
        // 不是包装给 Agent 看的 content[0].text 字符串——这是同一份数据流向两个不同
        // 消费者的两种不同表示，重构时很容易在这里把线接错（比如手滑把包装后的字符串
        // 传给了 recordSuccess，审计里存的就成了一段对人类友好但对统计无用的包装文本）。
        verify(auditService, times(1))
                .recordSuccess(isNull(), isNull(), eq("query_order"), any(), eq(rawResult), anyLong());
        verify(auditService, never()).recordError(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void toolsCall_successWithStringResult_passesStringThroughDirectly_withoutJsonSerialization() throws Exception {
        McpTool tool = stubRegisteredTool("query_payment_status", List.of("merchant", "cs", "admin"));
        when(tool.execute(any())).thenReturn("PAID");

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-9","method":"tools/call","params":{"name":"query_payment_status","arguments":{}}}
                """, "cs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                // String 结果应直接透传，不应该被多包一层 JSON 引号变成 "\"PAID\""
                .andExpect(jsonPath("$.result.content[0].text").value("PAID"));

        verify(auditService, times(1))
                .recordSuccess(isNull(), isNull(), eq("query_payment_status"), any(), eq("PAID"), anyLong());
    }

    // =====================================================================
    // 6. tools/call —— 4 种 typed exception + 通用异常到 McpError 的映射
    //    每一种都必须同步触发 auditService.recordError(...)
    // =====================================================================

    @Test
    void toolsCall_toolParameterException_mapsToParameterError_withDetailAndHintFromException() throws Exception {
        McpTool tool = stubRegisteredTool("query_order", List.of("merchant", "cs", "admin"));
        when(tool.execute(any()))
                .thenThrow(new McpTool.ToolParameterException("order_id 不能为空", "格式：ORDER_{数字}"));

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-10","method":"tools/call","params":{"name":"query_order","arguments":{}}}
                """, "merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("parameter_error"))
                .andExpect(jsonPath("$.error.data.detail").value("order_id 不能为空"))
                .andExpect(jsonPath("$.error.data.hint").value("格式：ORDER_{数字}"));

        verify(auditService).recordError(isNull(), isNull(), eq("query_order"), any(), eq("order_id 不能为空"), anyLong());
        verify(auditService, never()).recordSuccess(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void toolsCall_toolPermissionException_mapsToPermissionDenied_withMessageFromException() throws Exception {
        McpTool tool = stubRegisteredTool("query_order", List.of("merchant", "cs", "admin"));
        when(tool.execute(any())).thenThrow(new McpTool.ToolPermissionException("无权查看他人门店订单"));

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-11","method":"tools/call","params":{"name":"query_order","arguments":{"order_id":"ORDER_999"}}}
                """, "merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("permission_denied"))
                .andExpect(jsonPath("$.error.data.detail").value("无权查看他人门店订单"));

        verify(auditService).recordError(isNull(), isNull(), eq("query_order"), any(), eq("无权查看他人门店订单"), anyLong());
    }

    @Test
    void toolsCall_toolNotFoundException_mapsToNotFound_withMessageFromException() throws Exception {
        McpTool tool = stubRegisteredTool("query_order", List.of("merchant", "cs", "admin"));
        when(tool.execute(any())).thenThrow(new McpTool.ToolNotFoundException("订单不存在：ORDER_999"));

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-12","method":"tools/call","params":{"name":"query_order","arguments":{"order_id":"ORDER_999"}}}
                """, "merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("not_found"))
                .andExpect(jsonPath("$.error.data.detail").value("订单不存在：ORDER_999"));

        verify(auditService).recordError(isNull(), isNull(), eq("query_order"), any(), eq("订单不存在：ORDER_999"), anyLong());
    }

    @Test
    void toolsCall_toolBusinessException_mapsToBusinessConflict_withMessageFromException() throws Exception {
        McpTool tool = stubRegisteredTool("execute_refund", List.of("cs", "admin"));
        when(tool.execute(any())).thenThrow(new McpTool.ToolBusinessException("订单状态为 REFUNDED，不能重复退款"));

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-13","method":"tools/call","params":{"name":"execute_refund","arguments":{"order_id":"ORDER_1"}}}
                """, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("business_conflict"))
                .andExpect(jsonPath("$.error.data.detail").value("订单状态为 REFUNDED，不能重复退款"));

        verify(auditService).recordError(isNull(), isNull(), eq("execute_refund"), any(),
                eq("订单状态为 REFUNDED，不能重复退款"), anyLong());
    }

    @Test
    void toolsCall_unexpectedRuntimeException_mapsToInternalError_andStillRecordsAuditError() throws Exception {
        // 不是上面 4 种已知类型的"意外"异常——验证兜底分支同样会被审计，
        // 不会因为是"没预料到的异常类型"就悄悄漏记，让排障时连一条日志都找不到。
        McpTool tool = stubRegisteredTool("query_order", List.of("merchant", "cs", "admin"));
        when(tool.execute(any())).thenThrow(new IllegalStateException("数据库连接超时"));

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"req-14","method":"tools/call","params":{"name":"query_order","arguments":{"order_id":"ORDER_1"}}}
                """, "merchant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32603))
                .andExpect(jsonPath("$.error.message").value("internal_error"))
                .andExpect(jsonPath("$.error.data.detail").value("数据库连接超时"));

        verify(auditService).recordError(isNull(), isNull(), eq("query_order"), any(), eq("数据库连接超时"), anyLong());
    }

    // =====================================================================
    // 7. tools/call —— buildContentResult 序列化各种形状的 tool result
    //
    //    所有 tool 返回值统一走 ObjectMapper.writeValueAsString()（String 例外，直通）：
    //    序列化后 readTree() 复核，任何异常都短路成 internal_error，
    //    不再用 .toString() 兜底（那会产出非法 JSON、让 Agent 解析炸掉）。
    //
    //    覆盖 6 种入参形状：Map（已被 section 5 的用例兼顾）、JsonNode、DTO、
    //    List、null、非法对象（触发 FAIL_ON_EMPTY_BEANS）。
    // =====================================================================

    /** 用于 DTO 序列化测试的简单 POJO。 */
    @Data
    static class OrderSummary {
        private final String orderId;
        private final int amount;
    }

    /** 无任何可见属性——确定性触发 FAIL_ON_EMPTY_BEANS（Jackson 默认开启），不依赖 mock ObjectMapper。 */
    private static final class Unserializable {
        @SuppressWarnings("unused")
        private final String hidden = "secret";
    }

    @Test
    void buildContentResult_jsonNodeResult_serializesAsValidJsonInTextField() throws Exception {
        McpTool tool = stubRegisteredTool("query_order", List.of("merchant", "cs", "admin"));
        JsonNode node = objectMapper.readTree("{\"status\":\"PAID\",\"amount\":2990}");
        when(tool.execute(any())).thenReturn(node);

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"r-jn","method":"tools/call","params":{"name":"query_order","arguments":{}}}
                """, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text", containsString("\"status\":\"PAID\"")))
                .andExpect(jsonPath("$.result.content[0].text", containsString("\"amount\":2990")));
    }

    @Test
    void buildContentResult_dtoResult_serializesAsValidJsonObject() throws Exception {
        McpTool tool = stubRegisteredTool("query_order", List.of("merchant", "cs", "admin"));
        when(tool.execute(any())).thenReturn(new OrderSummary("ORD-001", 999));

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"r-dto","method":"tools/call","params":{"name":"query_order","arguments":{}}}
                """, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("\"orderId\":\"ORD-001\"")))
                .andExpect(jsonPath("$.result.content[0].text", containsString("\"amount\":999")));
    }

    @Test
    void buildContentResult_listResult_serializesAsJsonArray() throws Exception {
        McpTool tool = stubRegisteredTool("query_order", List.of("merchant", "cs", "admin"));
        when(tool.execute(any())).thenReturn(List.of("item_a", "item_b", "item_c"));

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"r-list","method":"tools/call","params":{"name":"query_order","arguments":{}}}
                """, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text").value("[\"item_a\",\"item_b\",\"item_c\"]"));
    }

    @Test
    void buildContentResult_nullResult_serializesAsJsonNull() throws Exception {
        McpTool tool = stubRegisteredTool("query_order", List.of("merchant", "cs", "admin"));
        when(tool.execute(any())).thenReturn(null);

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"r-null","method":"tools/call","params":{"name":"query_order","arguments":{}}}
                """, "admin"))
                .andExpect(status().isOk())
                // null → objectMapper.writeValueAsString(null) = "null"（JSON null 字面量）
                // 比空字符串更明确地向 Agent 表达"工具没有返回任何数据"
                .andExpect(jsonPath("$.result.content[0].text").value("null"));
    }

    @Test
    void buildContentResult_unserializableResult_returnsInternalError_ratherThanSilentlyProducingInvalidJson() throws Exception {
        // 回归点：旧实现 catch(Exception e) { text = toolResult.toString(); }
        // .toString() 的结果不是合法 JSON，会让 Agent 端 json.loads() 直接炸掉。
        // 修复后：序列化失败直接短路成 internal_error，不做危险兜底。
        McpTool tool = stubRegisteredTool("query_order", List.of("merchant", "cs", "admin"));
        when(tool.execute(any())).thenReturn(new Unserializable());

        mockMvc.perform(mcpRequest("""
                {"jsonrpc":"2.0","id":"r-bad","method":"tools/call","params":{"name":"query_order","arguments":{}}}
                """, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32603))
                .andExpect(jsonPath("$.error.message").value("internal_error"))
                .andExpect(jsonPath("$.error.data.detail", containsString("工具返回值序列化失败")));

        // 序列化阶段（tool.execute() 已经成功）的失败不走 recordError——
        // tool.execute() 正常返回，失败发生在 Controller 内部的包装环节
        verify(auditService, times(1))
                .recordSuccess(any(), any(), eq("query_order"), any(), any(), anyLong());
    }
}
