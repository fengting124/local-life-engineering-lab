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
 * + 本地 MySQL，实锤一个只有在异步语境下才会现形的缺陷。
 *
 * <h2>核心发现：RbacContext 在 @Async 方法体内永远是 null</h2>
 * <p>{@code recordSuccess}/{@code recordError} 都标了
 * {@link org.springframework.scheduling.annotation.Async @Async}。Spring 的异步代理
 * 会把"整个方法体"——包括方法体顶部那行 {@code RbacContext ctx = RbacContext.get();}——
 * 整个派发到独立线程池的某条线程上执行。而 {@link RbacContext} 的存储是一个
 * 普通的（非 {@code Inheritable}）{@code ThreadLocal}：调用线程上 set 进去的值，
 * 在执行线程上读到的必然是 {@code null}，因为读写根本不是同一条线程。
 * {@code LocalLifeCopilotApplication} 上没有任何自定义 {@code AsyncConfigurer}/
 * {@code TaskDecorator}/线程池 Bean（grep 过，确认是 Spring Boot 默认异步执行器），
 * 没有人做跨线程的上下文传播。
 *
 * <p>这个 bug <b>只能在真实 Spring 容器、真正触发异步派发时才能观察到</b>：
 * 普通单元测试里 {@code new ToolAuditService(...)} 直接调用 {@code recordSuccess}，
 * 整个调用同步跑在当前线程上，{@code RbacContext.get()} 自然能读到值——
 * 测试会"通过"，但通过的理由和生产环境南辕北辙，bug 被严丝合缝地掩盖。
 * 这正是这条用例必须是集成测试、不能是单元测试的原因。
 *
 * <h2>意外的复合发现：表结构漂移把"响亮失败"变成了"安静腐化"</h2>
 * <p>按 {@code V1__init_copilot_tables.sql} 的声明，{@code user_id}/{@code user_role}/
 * {@code session_id} 三列都是 {@code NOT NULL}——如果当真如此，上面的 bug 会让
 * 每一条审计 INSERT 都因为"列不能为空"报错，{@code record()} 里的
 * {@code catch (Exception e)} 会把异常吞掉、只打一条 WARN，最终现象是
 * {@code tool_audit_log} 永远空着，并伴随刷屏的 "[ToolAudit] 审计日志写入失败"。
 *
 * <p>但开发过程中用一次性探针 + {@code information_schema.columns} 实测发现：
 * 本地库里这三列实际是 {@code nullable=YES}——和迁移脚本的声明对不上（且探针确认
 * {@code sql_mode} 里 {@code STRICT_TRANS_TABLES} 是开着的，并不是因为关闭了严格模式）。
 * {@code init-db.sh} 用的是自制的 {@code schema_migrations} 流水账表，只记录
 * "这个版本号是否执行过"，没有任何 checksum 校验——这种机制天然没有能力发现
 * "已经记录为执行过的迁移文件，和库里实际的表结构对不上"这类漂移。
 *
 * <p>两个缺陷一复合，后果从"响亮失败"变成了"安静腐化"：表里查得到记录，
 * {@code tool_input}/{@code tool_output}/{@code status} 等字段看起来都正常，
 * 唯独 {@code user_id}/{@code user_role} 永远是 {@code null}——没有异常、没有告警、
 * 没有任何信号。而这张表存在的首要目的之一恰恰就是"事后能查清是谁、什么角色
 * 调用了退款 / 补偿券这类高风险动作"。这比"表是空的"更隐蔽，也更危险：
 * 空表至少会让人起疑，半残的表只会让人误以为一切正常。
 *
 * <h2>为什么不用 @Transactional（区别于 AuthJourneyIntegrationTest 的写法）</h2>
 * <p>{@code AuthJourneyIntegrationTest} 用 {@code @Transactional} 让每个用例的写入
 * 在结束时自动回滚，这里不能照搬，原因有两条都是命门：
 * <ol>
 *   <li><b>异步写发生在另一条线程、另一个连接上</b>，有它自己独立的事务，
 *       commit 早已落盘——测试方法所在事务的 rollback 根本够不到它，
 *       数据会真实、永久地留在本地开发库里；</li>
 *   <li><b>更隐蔽：MySQL {@code REPEATABLE READ} 的一致性读快照。</b>
 *       倘若测试方法本身开着事务，它的快照在第一条 SELECT 时就已经定格；
 *       哪怕异步线程随后在另一个连接上 commit 了新行，本事务内的轮询 SELECT
 *       也永远看不到——不是变慢，是<b>永远等不到</b>，测试会一路挂到超时，
 *       变成一个"看似随机失败、实则必然失败"的诡异 flaky case。</li>
 * </ol>
 * <p>所以这里彻底不开事务，换成 {@link #cleanup()} 按本用例独有的
 * {@code uniqueToolName} 手动删除——和 {@code AuthJourneyIntegrationTest}
 * 手动清 Redis 是同一种哲学：{@code @Transactional} 触不到的角落，老老实实手动收尾。
 *
 * <h2>定性：这是一条"特征化测试"，不是回归测试</h2>
 * <p>下面的断言记录的是<b>当前实际观察到的行为</b>，不是"期望中的正确行为"——
 * 因为 bug 本身没有被修复（修复需要架构决策：在调用线程上提前捕获 RbacContext
 * 显式传给异步方法、换成 {@code InheritableThreadLocal}，还是补一个透传上下文的
 * {@code TaskDecorator}，这是"需要用户决策"的范畴，不在本次测试覆盖任务之内）。
 * 一旦上面两个缺陷中的任何一个将来被修掉，本类的断言就会随之失败——这是有意
 * 设下的"绊线"：它在提醒维护者"这里的特征变了，回来看一眼、更新或删除这条用例"，
 * 而不是要求"必须让它永远变绿"。
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
    void recordSuccess_losesRbacContextAcrossAsyncBoundary_soPersistedRowEndsUpWithNullUserIdAndUserRole(
            CapturedOutput output) throws Exception {

        // ---- Arrange：在调用线程——也就是这条测试方法自己的线程——上正确设置好身份上下文 ----
        RbacContext.set(RbacContext.builder().userId(TEST_USER_ID).role(TEST_ROLE).build());
        RbacContext sanityCheck = RbacContext.get();
        assertThat(sanityCheck)
                .as("健全性检查：上下文确实已经挂在了*这条线程*上——下面如果在落地的行里读到 null，"
                        + "那就一定是跨线程弄丢的，不是一开始就没 set")
                .isNotNull();
        assertThat(sanityCheck.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(sanityCheck.getRole()).isEqualTo(TEST_ROLE);

        JsonNode toolInput = objectMapper.readTree("{\"order_id\":\"1234567890123456789\"}");
        Map<String, Object> toolOutput = Map.of("order_status", "PAID", "pay_status", "SUCCESS");

        // ---- Act：触发 @Async 方法——方法体（含其顶部的 RbacContext.get()）会被丢到执行器线程上跑 ----
        auditService.recordSuccess(TEST_SESSION_ID, TEST_THREAD_ID, uniqueToolName, toolInput, toolOutput, TEST_DURATION_MS);

        // ---- Assert 1：轮询等行落地。
        //      它会落地——这既是 @Async 真异步完成的证据，也是"表结构漂移"的间接物证：
        //      按迁移脚本里 user_id/user_role NOT NULL 的声明，ctx==null 本应让这条 INSERT
        //      失败在数据库这一关；它没有失败，说明本地库里这两列的真实约束和迁移脚本的
        //      声明对不上（开发过程中已经用 information_schema 探针验证：实际是 nullable=YES，
        //      且 sql_mode 仍然包含 STRICT_TRANS_TABLES——并不是严格模式被关掉了）。----
        ToolAuditLog row = pollForAuditRow(uniqueToolName, Duration.ofSeconds(5));
        assertThat(row)
                .as("审计行最终应当落地（@Async 是真正的异步派发，不是同步阻塞——给它一点轮询时间）")
                .isNotNull();

        // ---- Assert 2：核心断言——构成"RbacContext 跨线程丢失"的实锤证据 ----
        // 上面已经证明：调用线程上的上下文是 (userId=88888, role="admin")。
        // 但落地的这一行——只可能来自执行器线程里跑的那次 record(...)——却是 null/null。
        // 同一份、本应全程跟随这次调用的身份信息，从调用线程到执行线程之间凭空消失了，
        // 这就是直接物证。
        assertThat(row.getUserId())
                .as("调用线程上 RbacContext.userId 明明是 %d，但落地的行里却是 null——"
                        + "因为 record() 内部那行 RbacContext.get() 实际运行在另一条（执行器）线程上，"
                        + "读到的是一个全新的、从未 set 过的 ThreadLocal 槽位", TEST_USER_ID)
                .isNull();
        assertThat(row.getUserRole())
                .as("同上——userRole 本应随上下文一起传过去是 \"%s\"，实际落地的却是 null", TEST_ROLE)
                .isNull();

        // ---- Assert 3：缺陷范围刻画——证明问题"只"出在 RBAC 派生的两个字段上，
        //      其余所有字段都原样、正确地落了地：管线本身是健康的，不是整体翻车 ----
        assertThat(row.getSessionId()).isEqualTo(TEST_SESSION_ID);
        assertThat(row.getThreadId()).isEqualTo(TEST_THREAD_ID);
        assertThat(row.getStatus()).isEqualTo("success");
        assertThat(row.getDurationMs()).isEqualTo(TEST_DURATION_MS);
        assertThat(row.getToolInput()).contains("1234567890123456789");
        assertThat(row.getToolOutput()).contains("PAID").contains("SUCCESS");

        // ---- Assert 4：而且这一切悄无声息——record() 里 catch(Exception) 那条 WARN 压根没触发，
        //      因为 INSERT 根本没抛异常（表结构漂移让 NOT NULL 约束形同虚设，"成功"了）。
        //      这正是"安静腐化"比"响亮失败"更难被发现的地方：日志、告警、监控
        //      都不会冒出任何异常信号——表面上一切正常，内里数据已经半残。----
        assertThat(output.getOut())
                .as("插入'成功'了（表结构漂移让 NOT NULL 约束形同虚设），"
                        + "所以 record() 里吞异常那条 WARN 日志根本不会被触发——"
                        + "整条链路没有任何报错或告警，数据就这样悄无声息地半残了")
                .doesNotContain("[ToolAudit] 审计日志写入失败");
    }

    /**
     * 轮询直到指定 {@code toolName} 的审计行出现，或超时后返回 {@code null}。
     *
     * <p>不引入 Awaitility——这个模块的 {@code pom.xml} 里压根没有它（已经 grep 确认过），
     * 手写一个 100ms 间隔的轮询循环足够简单可靠，不值得为这一处用例新增依赖。
     */
    private ToolAuditLog pollForAuditRow(String toolName, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            var rows = toolAuditMapper.selectList(
                    new LambdaQueryWrapper<ToolAuditLog>().eq(ToolAuditLog::getToolName, toolName));
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(100);
        }
        return null;
    }
}
