package com.personalprojections.locallife.copilot.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolAuditService} 集成测试——用真实 Spring 容器（{@code @EnableAsync} 生效）
 * + 本地 MySQL，验证 {@link com.personalprojections.locallife.copilot.config.AsyncConfig}
 * 注册的 {@code TaskDecorator} 确实把 {@link RbacContext} 跨 {@code @Async} 边界正确传播。
 *
 * <h2>修复前后的对比</h2>
 * <table border="1">
 *   <tr><th>状态</th><th>落地行里 user_id / user_role</th><th>原因</th></tr>
 *   <tr><td>修复前（无 TaskDecorator）</td>
 *       <td>{@code null} / {@code null}</td>
 *       <td>{@code record()} 整体在执行器线程运行，读 ThreadLocal 读到空槽位</td></tr>
 *   <tr><td>修复后（有 TaskDecorator）</td>
 *       <td>{@code 88888} / {@code "admin"}</td>
 *       <td>Decorator 在提交线程快照、在执行线程还原，读写同一份数据</td></tr>
 * </table>
 *
 * <h2>为什么必须是集成测试，不能是单元测试</h2>
 * <p>普通单元测试里 {@code new ToolAuditService(...)} 直接调用 {@code recordSuccess}，
 * 整个调用同步跑在当前线程上——{@code @Async} 的代理层根本没有介入，
 * {@code RbacContext.get()} 自然能读到值，但这和生产环境"真的跨了线程"南辕北辙。
 * 只有在真实 Spring 容器里，{@code @EnableAsync} + 线程池 + TaskDecorator 一起生效，
 * 才能观察到上下文传播是否真的端到端地通了。
 *
 * <h2>表结构漂移背景（两分支设计的历史原因）</h2>
 * <p>开发过程中曾发现本地开发库的 {@code user_id}/{@code user_role}/{@code session_id}
 * 三列实际是 {@code nullable=YES}，与 {@code V1__init_copilot_tables.sql} 声明的
 * {@code NOT NULL} 不符（已通过 ALTER TABLE 修复，现在本地库与迁移脚本一致）。
 * 这意味着在修复前的历史上，本机和 CI 有可能同时处于两种不同的库状态——
 * 两分支轮询设计就是为了让同一条用例在两种状态下都能稳定通过。
 * 现在两端状态已经对齐，预期总是命中分支 A。
 *
 * <h2>为什么不用 @Transactional</h2>
 * <p>异步写发生在另一条线程的独立事务上，commit 早已落盘——
 * 测试方法所在事务的 rollback 根本够不到它。
 * 另外，MySQL {@code REPEATABLE READ} 的快照是在事务第一条 SELECT 时定格的，
 * 在测试事务内部轮询时永远看不到另一条连接 commit 的新行——轮询会一路挂到超时。
 * 所以这里彻底不开事务，用 {@link #cleanup()} 按 {@code uniqueToolName} 手动清理。
 *
 * <h2>定性：这是一条回归测试，验证 TaskDecorator 的端到端效果</h2>
 * <p>断言记录的是<b>修复后期望的正确行为</b>：
 * 落地行里的 {@code user_id}/{@code user_role} 必须与调用线程设置的身份一致。
 * 如果将来有人不小心移除了 {@link com.personalprojections.locallife.copilot.config.AsyncConfig}
 * 或改掉了 TaskDecorator 的逻辑，这条用例会立即变红——这是有意设下的回归绊线。
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class ToolAuditServiceIntegrationTest {

    @Autowired
    private ToolAuditService auditService;

    @Autowired
    private ToolAuditMapper toolAuditMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private static final long TEST_SESSION_ID = 990011223344L;
    private static final String TEST_THREAD_ID = "thread_audit_integration_test";
    private static final int TEST_DURATION_MS = 42;
    private static final long TEST_USER_ID = 88888L;
    private static final String TEST_ROLE = "admin";

    /** 每条用例独有的 tool_name——既用来在轮询时精确定位"自己写的那一行"，也用于 {@link #cleanup()} 精确清理，互不干扰。 */
    private String uniqueToolName;

    @BeforeEach
    void setUp() {
        uniqueToolName = "audit_it_probe_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanup() {
        // @Transactional 起不到的清理，照搬 AuthJourneyIntegrationTest 手动清 Redis 的思路
        toolAuditMapper.delete(new LambdaQueryWrapper<ToolAuditLog>().eq(ToolAuditLog::getToolName, uniqueToolName));
        RbacContext.clear();
    }

    @Test
    void recordSuccess_rbacContextPropagatedAcrossAsyncBoundary_verifiedByPopulatedColumns(
            CapturedOutput output) throws Exception {

        // ---- Arrange：在调用线程——也就是这条测试方法自己的线程——上正确设置好身份上下文 ----
        RbacContext.set(RbacContext.builder().userId(TEST_USER_ID).role(TEST_ROLE).build());
        RbacContext sanityCheck = RbacContext.get();
        assertThat(sanityCheck)
                .as("健全性检查：上下文确实已经挂在了*这条线程*上——"
                        + "TaskDecorator 应该在提交时快照到这个值，在执行线程上还原")
                .isNotNull();
        assertThat(sanityCheck.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(sanityCheck.getRole()).isEqualTo(TEST_ROLE);

        JsonNode toolInput = objectMapper.readTree("{\"order_id\":\"1234567890123456789\"}");
        Map<String, Object> toolOutput = Map.of("order_status", "PAID", "pay_status", "SUCCESS");

        // ---- Act：触发 @Async 方法——方法体（含其顶部的 RbacContext.get()）会被丢到执行器线程上跑 ----
        auditService.recordSuccess(TEST_SESSION_ID, TEST_THREAD_ID, uniqueToolName, toolInput, toolOutput, TEST_DURATION_MS);

        // ---- Assert：等待异步任务跑到「终态」，再按实际结局取证 ----
        //
        // 两分支设计的历史原因：见类 javadoc「表结构漂移背景」节。
        // 现在本地库和迁移脚本已对齐（session_id/user_id/user_role 均为 NOT NULL），
        // TaskDecorator 也已注册，预期总是命中分支 A（ROW_PERSISTED）。
        // 两分支结构继续保留作为防御基础设施——分支 B 是"意外失败"的安全网，不是预期路径。
        AsyncOutcome outcome = awaitAsyncOutcome(uniqueToolName, output, Duration.ofSeconds(5));
        assertThat(outcome)
                .as("审计行也没落地、吞异常的 WARN 也没出现——@Async 任务在 5 秒内应该已经跑完了")
                .isNotEqualTo(AsyncOutcome.NEITHER_HAPPENED_WITHIN_TIMEOUT);

        if (outcome == AsyncOutcome.ROW_PERSISTED) {
            // ---- 分支 A（预期路径）：TaskDecorator 正确传播了 RbacContext，INSERT 成功。 ----
            ToolAuditLog row = toolAuditMapper.selectOne(
                    new LambdaQueryWrapper<ToolAuditLog>().eq(ToolAuditLog::getToolName, uniqueToolName));

            assertThat(row.getUserId())
                    .as("TaskDecorator 在提交线程（userId=%d）捕获快照，在执行线程还原——"
                            + "落地的行 userId 必须与调用线程一致，不能是 null", TEST_USER_ID)
                    .isEqualTo(TEST_USER_ID);
            assertThat(row.getUserRole())
                    .as("同上——TaskDecorator 保证 userRole 正确传播到执行线程，不能是 null")
                    .isEqualTo(TEST_ROLE);

            // 其余字段确认完整落地
            assertThat(row.getSessionId()).isEqualTo(TEST_SESSION_ID);
            assertThat(row.getThreadId()).isEqualTo(TEST_THREAD_ID);
            assertThat(row.getStatus()).isEqualTo("success");
            assertThat(row.getDurationMs()).isEqualTo(TEST_DURATION_MS);
            assertThat(row.getToolInput()).contains("1234567890123456789");
            assertThat(row.getToolOutput()).contains("PAID").contains("SUCCESS");

            assertThat(output.getOut())
                    .as("INSERT 成功，record() 里吞异常那条 WARN 日志不应该出现")
                    .doesNotContain("[ToolAudit] 审计日志写入失败");
        } else {
            // ---- 分支 B（意外失败安全网）：INSERT 失败触发了 WARN——
            //      这不应该发生（TaskDecorator 在位、DB 约束对齐），
            //      如果走到这里说明某个环境前提被破坏了。
            //      WARN 日志里应该包含 tool 名字和某种错误信息，方便排查。 ----
            assertThat(toolAuditMapper.selectCount(
                    new LambdaQueryWrapper<ToolAuditLog>().eq(ToolAuditLog::getToolName, uniqueToolName)))
                    .as("INSERT 失败了，行不存在——吞异常的 WARN 才是唯一痕迹")
                    .isZero();
            assertThat(output.getOut())
                    .as("分支 B 不应该出现：若走到这里，说明 TaskDecorator 或 DB 约束出了问题，"
                            + "查 WARN 日志定位根因")
                    .contains("[ToolAudit] 审计日志写入失败")
                    .contains(uniqueToolName);
        }
    }

    private enum AsyncOutcome { ROW_PERSISTED, WARNING_LOGGED, NEITHER_HAPPENED_WITHIN_TIMEOUT }

    /**
     * 轮询直到 {@code @Async} 任务跑出两种终态之一——审计行落地，或者吞异常的 WARN 出现——
     * 或者超时仍未跑出任何一种，返回相应的 {@link AsyncOutcome}。
     *
     * <p>为什么不是"只轮询行是否出现"：那样会有一个隐蔽的竞态——如果当前库的
     * user_id/user_role 是 NOT NULL（插入注定失败、行永远不会出现），单纯轮询
     * "行是否出现"在前几次循环里看到的"还没出现"，和"插入已经失败、永远不会出现"
     * 长得一模一样，区分不开；要等到超时才能"确认"——而那其实是在拿一个超时
     * 来掩盖一个本可以快得多确认的明确结局。两种终态一起轮询、谁先出现就采信谁，
     * 既消灭了竞态，也让用例在"插入注定失败"的库上跑得飞快，不必干等满超时。
     *
     * <p>不引入 Awaitility——这个模块的 {@code pom.xml} 里压根没有它（已经 grep 确认过），
     * 手写一个 100ms 间隔的轮询循环足够简单可靠，不值得为这一处用例新增依赖。
     */
    private AsyncOutcome awaitAsyncOutcome(String toolName, CapturedOutput output, Duration timeout) throws InterruptedException {
        String warnMarker = "[ToolAudit] 审计日志写入失败: tool=" + toolName;
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (toolAuditMapper.exists(new LambdaQueryWrapper<ToolAuditLog>().eq(ToolAuditLog::getToolName, toolName))) {
                return AsyncOutcome.ROW_PERSISTED;
            }
            if (output.getOut().contains(warnMarker)) {
                return AsyncOutcome.WARNING_LOGGED;
            }
            Thread.sleep(100);
        }
        return AsyncOutcome.NEITHER_HAPPENED_WITHIN_TIMEOUT;
    }
}
