package com.personalprojections.locallife.copilot.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link ToolAuditService} 单元测试——聚焦 {@code toJson(...)} 的序列化契约。
 *
 * <h2>背景：toJson 曾经存在的"未经校验直接落库"缺口</h2>
 * <p>{@code tool_input}/{@code tool_output} 是 {@code JSON NOT NULL} 列。修复前的实现是：
 * <pre>
 * if (obj instanceof String s) return s;
 * ...
 * } catch (JsonProcessingException e) {
 *     return obj.toString();
 * }
 * </pre>
 * 两条分支都不保证产出<b>合法 JSON</b>——前者对裸字符串不做任何校验就原样返回，
 * 后者的 {@code .toString()} 兜底同样可能产出非法 JSON。只要现实里出现这两种输入之一，
 * 落库时就会触发 {@code MysqlDataTruncation: Invalid JSON text}
 * （开发过程中用探针对着真实 MySQL 验证过这个失败现象本身是真实可触发的——
 * 当时用 {@code "in"}/{@code "out"} 这类裸字符串做测试数据，第一次跑探针就直接炸了出来）。
 *
 * <p>本类用 Mockito 截获传给 {@link ToolAuditMapper#insert} 的 {@link ToolAuditLog}，
 * 钉住修复后的契约：无论输入是什么形状，{@code toJson} 的输出要么是 {@code null}，
 * 要么必须是 ObjectMapper 自己也能解析回去的合法 JSON 文本。
 *
 * <h2>为什么是普通单元测试，不是 @SpringBootTest</h2>
 * <p>{@code recordSuccess}/{@code recordError} 标了 {@code @Async}，但 {@code @Async}
 * 只有在 Spring 用代理包装 bean 时才会生效——这里直接 {@code new ToolAuditService(...)}，
 * 调用落在当前线程上同步执行，和 {@code IssueCompensationCouponToolTest} 等工具测试
 * 用 mock 依赖直接 new 的写法是同一个套路，不需要真实数据库、不需要异步等待。
 * （跨 {@code @Async} 边界的问题——RbacContext 丢失——已经由
 * {@link ToolAuditServiceIntegrationTest} 用真实容器单独证明，这里不重复关注它。）
 */
class ToolAuditServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolAuditMapper toolAuditMapper = mock(ToolAuditMapper.class);
    private final ToolAuditService service = new ToolAuditService(toolAuditMapper, objectMapper);

    /** 没有任何公开属性/getter 的私有嵌套类——确定性地触发 Jackson 默认开启的 FAIL_ON_EMPTY_BEANS。 */
    private static final class Unserializable {
        @SuppressWarnings("unused")
        private final String hidden = "secret";
    }

    private ToolAuditLog captureInsertedLog() {
        ArgumentCaptor<ToolAuditLog> captor = ArgumentCaptor.forClass(ToolAuditLog.class);
        verify(toolAuditMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordSuccess_withMapInputAndOutput_serializesAsJsonObjects() {
        service.recordSuccess(1L, "thread-1", "tool_x", Map.of("a", 1), Map.of("b", "c"), 10);

        ToolAuditLog log = captureInsertedLog();
        assertThat(log.getToolInput()).isEqualTo("{\"a\":1}");
        assertThat(log.getToolOutput()).isEqualTo("{\"b\":\"c\"}");
    }

    @Test
    void recordSuccess_withRawValidJsonStringInput_storesAsIs_withoutDoubleEncoding() {
        service.recordSuccess(1L, "thread-1", "tool_x", "{\"already\":\"json\"}", Map.of(), 10);

        assertThat(captureInsertedLog().getToolInput())
                .as("已经是合法 JSON 的字符串——原样存，不应该被再包一层引号、变成 JSON 字符串字面量")
                .isEqualTo("{\"already\":\"json\"}");
    }

    @Test
    void recordSuccess_withRawNonJsonStringInput_wrapsAsJsonStringLiteral_insteadOfStoringInvalidJson() {
        // 回归点：旧实现 `if (obj instanceof String s) return s;` 不区分是否合法 JSON，
        // 直接原样返回——写进 JSON NOT NULL 列会触发 MysqlDataTruncation: Invalid JSON text。
        // 修复后：非法 JSON 的裸字符串落入正常序列化分支，被 Jackson 转成
        // 带引号转义的 JSON 字符串字面量——本身就是合法 JSON。
        service.recordSuccess(1L, "thread-1", "tool_x", "plain text, not json", Map.of(), 10);

        assertThat(captureInsertedLog().getToolInput()).isEqualTo("\"plain text, not json\"");
    }

    @Test
    void recordSuccess_withNullInputAndOutput_storesNull() {
        service.recordSuccess(1L, "thread-1", "tool_x", null, null, 10);

        ToolAuditLog log = captureInsertedLog();
        assertThat(log.getToolInput()).isNull();
        assertThat(log.getToolOutput()).isNull();
    }

    @Test
    void recordSuccess_withUnserializableOutput_fallsBackToJsonStringLiteral_insteadOfStoringInvalidJson() {
        // 回归点：旧实现 `catch (JsonProcessingException e) { return obj.toString(); }`——
        // .toString() 的结果同样不保证是合法 JSON。这里用一个 Jackson 默认就会拒绝序列化的
        // "空 Bean"（FAIL_ON_EMPTY_BEANS 默认开启：没有任何可见属性）确定性地触发该分支，
        // 不需要 mock ObjectMapper 内部、不需要构造离谱的循环引用。
        service.recordSuccess(1L, "thread-1", "tool_x", Map.of(), new Unserializable(), 10);

        String stored = captureInsertedLog().getToolOutput();
        assertThat(stored)
                .as("退化结果必须本身就是合法 JSON——交给 ObjectMapper 复核一遍，"
                        + "解析不出来就说明又把非法内容塞回了 JSON 列")
                .satisfies(json -> assertThatCode(() -> objectMapper.readTree(json)).doesNotThrowAnyException());
        assertThat(stored)
                .as("且应当是一个 JSON 字符串字面量（被引号包住），而不是裸 toString() 的原始输出")
                .startsWith("\"")
                .endsWith("\"");
    }
}
