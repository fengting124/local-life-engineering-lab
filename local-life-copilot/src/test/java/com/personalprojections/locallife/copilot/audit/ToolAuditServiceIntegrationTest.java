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
 * <h2>为什么下面的用例写成"两条分支、择一证明"，而不是只断言其中一种结局</h2>
 * <p>同一个根因（{@code ctx == null}）落到数据库里会表现成上面两种症状里的<b>哪一种</b>，
 * 完全取决于 {@code user_id}/{@code user_role} 这两列<b>当下到底是不是真的 NOT NULL</b>——
 * 而这恰恰是上一节里揭出的、与本测试主题正交的环境差异：本地这台开发库已经漂移成了
 * {@code nullable=YES}（插入"成功"，安静腐化），但 {@code init-db.sh} 在一张白板新库
 * 上根据当前迁移脚本建出来的表大概率仍是 {@code NOT NULL}（插入失败，响亮报警）——
 * 也就是说，本机和 CI 很可能正好分别处在这两种状态的两端。
 *
 * <p>如果只硬编码断言其中一种结局（比如"行一定会落地，且 userId/userRole 是 null"），
 * 这条用例会在本机稳定通过、却可能在 CI 上单纯因为"行永远不会出现"而超时变红——
 * 一个自己给自己挖的坑：变红的原因和它本来要证明的事情（RbacContext 跨线程丢失）
 * 毫无关系，只是顺带、笨拙地暴露了一个它自己都没准备去断言的环境差异，
 * 沦为又一条"莫名其妙就是过不了 CI"的迷惑性用例。
 *
 * <p>所以正确的做法是：用 {@link #awaitAsyncOutcome} 同时轮询两种终态信号——
 * 行落地，或者 {@code record()} 里那条吞异常的 WARN 出现——谁先现身就采信谁，
 * 然后分别在各自的分支里取证。两条分支殊途同归，独立地指向同一个根因：
 * <ul>
 *   <li><b>分支 A（行落地、字段是 null）</b>：直接物证——调用线程上 ctx 明明是
 *       {@code (88888, "admin")}，落地的行却是 {@code null}/{@code null}；</li>
 *   <li><b>分支 B（行没落地、WARN 里点名 user_id/user_role 不能为空）</b>：间接但同样
 *       确凿的物证——如果 ctx 真的有效，{@code ToolAuditLog} 的这两个字段就不会是
 *       {@code null}，NOT NULL 约束也就不会被触发；唯一能解释"DB 抱怨 user_id 是
 *       null"的原因，就是 {@code record()} 内部读到的 ctx 本身是 {@code null}。</li>
 * </ul>
 * <p>无论运行在哪一种库状态下，这条用例都应该用绿色断言指出同一个根因——
 * 而不是被一个它自己都不知道存在的环境差异，意外地染红或染绿。
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
    void recordSuccess_losesRbacContextAcrossAsyncBoundary_provenEitherByNullColumnsOrBySwallowedWarning(
            CapturedOutput output) throws Exception {

        // ---- Arrange：在调用线程——也就是这条测试方法自己的线程——上正确设置好身份上下文 ----
        RbacContext.set(RbacContext.builder().userId(TEST_USER_ID).role(TEST_ROLE).build());
        RbacContext sanityCheck = RbacContext.get();
        assertThat(sanityCheck)
                .as("健全性检查：上下文确实已经挂在了*这条线程*上——下面无论走哪个分支读到「身份信息消失了」，"
                        + "那都一定是跨线程弄丢的，不是一开始就没 set")
                .isNotNull();
        assertThat(sanityCheck.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(sanityCheck.getRole()).isEqualTo(TEST_ROLE);

        JsonNode toolInput = objectMapper.readTree("{\"order_id\":\"1234567890123456789\"}");
        Map<String, Object> toolOutput = Map.of("order_status", "PAID", "pay_status", "SUCCESS");

        // ---- Act：触发 @Async 方法——方法体（含其顶部的 RbacContext.get()）会被丢到执行器线程上跑 ----
        auditService.recordSuccess(TEST_SESSION_ID, TEST_THREAD_ID, uniqueToolName, toolInput, toolOutput, TEST_DURATION_MS);

        // ---- Assert：等待异步任务跑到「终态」，再按它实际落到了哪一种结局分别取证 ----
        //
        // 为什么写成两条分支、而不是直接断言"行一定会落地、且 userId/userRole 是 null"：
        //
        // 同一个根因（ctx==null）在数据库里到底会表现成"插入成功但身份字段是 null"
        // 还是"插入失败、整行都不存在"，完全取决于 user_id/user_role 这两列当下
        // 是不是真的 NOT NULL——而这一点，恰恰是开发过程中意外揭出的*另一个*、
        // 与本测试主题正交的发现：本地开发库里这两列已经漂移成了 nullable=YES
        // （用 information_schema 探针实测验证过，sql_mode 仍含 STRICT_TRANS_TABLES，
        // 不是严格模式被关掉了），但 V1__init_copilot_tables.sql 仍然声明 NOT NULL——
        // 也就是说，CI 用 init-db.sh 在白板新库上跑出来的表，大概率会和迁移脚本一致
        // （NOT NULL），和本地这台已经漂移的库不是同一种状态。
        //
        // 如果只断言"行会落地、且字段是 null"，这条用例会在本地稳定通过、却在 CI 上
        // 大概率因为「行根本不会出现」而超时失败——一个自己挖坑自己跳的脆弱用例：
        // 红的原因和它本来要证明的事情（RbacContext 跨线程丢失）毫无关系，
        // 只是顺带暴露了一个它自己都没准备去断言的、关于库表结构的环境差异。
        // 让它在两种库状态下都能立住、都各自独立地指向同一个根因，才是稳妥的写法。
        AsyncOutcome outcome = awaitAsyncOutcome(uniqueToolName, output, Duration.ofSeconds(5));
        assertThat(outcome)
                .as("审计行也没落地、吞异常的 WARN 也没出现——@Async 任务在 5 秒内应该已经跑完了")
                .isNotEqualTo(AsyncOutcome.NEITHER_HAPPENED_WITHIN_TIMEOUT);

        if (outcome == AsyncOutcome.ROW_PERSISTED) {
            // ---- 分支 A：插入"成功"了（本地开发库目前就是这种状态——
            //      user_id/user_role 实际是 nullable=YES，和迁移脚本的声明对不上）。
            //      核心物证：调用线程上 ctx 明明是 (88888, "admin")，落地的行却是 null/null。 ----
            ToolAuditLog row = toolAuditMapper.selectOne(
                    new LambdaQueryWrapper<ToolAuditLog>().eq(ToolAuditLog::getToolName, uniqueToolName));

            assertThat(row.getUserId())
                    .as("调用线程上 RbacContext.userId 明明是 %d，但落地的行里却是 null——"
                            + "因为 record() 内部那行 RbacContext.get() 实际运行在另一条（执行器）线程上，"
                            + "读到的是一个全新的、从未 set 过的 ThreadLocal 槽位", TEST_USER_ID)
                    .isNull();
            assertThat(row.getUserRole())
                    .as("同上——userRole 本应随上下文一起传过去是 \"%s\"，实际落地的却是 null", TEST_ROLE)
                    .isNull();

            // 缺陷范围刻画：其余字段都原样、正确地落了地，问题"只"出在 RBAC 派生字段上，
            // 不是管线整体翻车——这一点决定了它有多容易被忽略：表面上全是正常数据。
            assertThat(row.getSessionId()).isEqualTo(TEST_SESSION_ID);
            assertThat(row.getThreadId()).isEqualTo(TEST_THREAD_ID);
            assertThat(row.getStatus()).isEqualTo("success");
            assertThat(row.getDurationMs()).isEqualTo(TEST_DURATION_MS);
            assertThat(row.getToolInput()).contains("1234567890123456789");
            assertThat(row.getToolOutput()).contains("PAID").contains("SUCCESS");

            // 而且全程悄无声息——catch(Exception) 那条 WARN 压根没触发，因为 INSERT 没抛异常。
            // 这正是"安静腐化"比"响亮失败"更难被发现的地方：日志、监控都不会冒出任何异常信号，
            // tool_input/output/status 等字段看起来都正常，唯独 user_id/user_role 永远是 null。
            assertThat(output.getOut())
                    .as("插入'成功'了，所以 record() 里吞异常那条 WARN 日志根本不会被触发——"
                            + "整条链路没有任何报错或告警，数据就这样悄无声息地半残了")
                    .doesNotContain("[ToolAudit] 审计日志写入失败");
        } else {
            // ---- 分支 B：插入失败了（user_id/user_role 真的是 NOT NULL，和迁移脚本一致——
            //      CI 用 init-db.sh 在白板新库上跑出来的表，大概率正是这种状态）。
            //      核心物证：被吞掉的异常明确指向"user_id/user_role 不能为 null"，
            //      而调用线程上 ctx 分明已经是 (88888, "admin")——
            //      唯一能解释"DB 抱怨 user_id 是 null"的原因，就是 record() 内部
            //      读到的 ctx 是 null，指向的是同一个根因，只是表现成了"响亮失败"
            //      而不是"安静腐化"（哪个更糟见类javadoc）。 ----
            assertThat(toolAuditMapper.selectCount(
                    new LambdaQueryWrapper<ToolAuditLog>().eq(ToolAuditLog::getToolName, uniqueToolName)))
                    .as("INSERT 在数据库这一关就失败了，所以这一行压根不存在——吞异常的 WARN 才是唯一痕迹")
                    .isZero();
            assertThat(output.getOut())
                    .as("ctx==null 会让 ToolAuditLog.userId/userRole 都是 null，插到 NOT NULL 列上"
                            + "必然在数据库这一关报错；record() 的 catch(Exception) 把它吞掉，"
                            + "只留下这条 WARN 作为唯一痕迹——异常本身也指名道姓地点出是 user_id/user_role"
                            + "不能为空，把『插入失败』钉死成『ctx 是 null』的证据，而不是 tool_input"
                            + "JSON 格式之类无关的偶发错误")
                    .contains("[ToolAudit] 审计日志写入失败")
                    .contains(uniqueToolName)
                    .containsAnyOf("user_id", "user_role", "Column", "cannot be null");
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
