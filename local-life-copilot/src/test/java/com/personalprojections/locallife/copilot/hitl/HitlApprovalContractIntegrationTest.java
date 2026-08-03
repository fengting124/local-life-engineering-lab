package com.personalprojections.locallife.copilot.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.client.LocalLifeInternalClient;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolBusinessException;
import com.personalprojections.locallife.copilot.tool.impl.ExecuteRefundTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class HitlApprovalContractIntegrationTest {

    private static final String SECRET = "test-only-hitl-key";
    private static final long APPROVAL_ID = 970000000001L;
    private static final Path REPOSITORY_ROOT = findRepositoryRoot();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("local_life")
            .withUsername("root")
            .withPassword("123456");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> String.join(",",
                filesystemLocation("local-life-server/src/main/resources/db/migration"),
                filesystemLocation("local-life-copilot/src/main/resources/db/migration")));
        registry.add("hitl.payload-signing.secret", () -> SECRET);
    }

    @Autowired
    private HitlApprovalMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private ApprovalPayload payload;
    private String digest;
    private ApprovalExecutionGuard guard;

    @BeforeEach
    void seedApproval() {
        payload = new ApprovalPayload(
                1, "execute_refund", "202606100003", 2000,
                "", "42", "1001", "admin", "integration approval"
        );
        ApprovalPayloadSigner signer = new ApprovalPayloadSigner(objectMapper, SECRET);
        digest = signer.sign(payload);
        guard = new ApprovalExecutionGuard(
                mapper,
                signer,
                objectMapper,
                Clock.systemUTC(),
                Duration.ofMinutes(2)
        );
        jdbcTemplate.update("DELETE FROM hitl_approval WHERE id = ?", APPROVAL_ID);
        jdbcTemplate.update("""
                INSERT INTO hitl_approval(
                    id, session_id, thread_id, checkpoint_id, action_type,
                    action_payload, payload_version, payload_digest,
                    order_target_hash, merchant_id, requested_user_id,
                    requested_role, agent_reason, status, expire_at
                ) VALUES (?, 1, 'thread-1', 'checkpoint-1', ?, ?, 1, ?,
                          REPEAT('a', 64), 42, 1001, 'admin',
                          'integration approval', 'APPROVED', NOW() + INTERVAL 1 HOUR)
                """, APPROVAL_ID, payload.toolName(), signer.canonicalJson(payload), digest);
    }

    @Test
    void v104ColumnsAndIndexesExist() {
        Integer columns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'hitl_approval'
                  AND column_name IN (
                    'payload_digest', 'order_target_hash', 'merchant_id',
                    'requested_user_id', 'requested_role', 'execution_id',
                    'execution_lease_until', 'execution_result'
                  )
                """, Integer.class);
        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'hitl_approval'
                """, String.class);

        assertThat(columns).isEqualTo(8);
        assertThat(indexes).contains("idx_hitl_status_lease", "idx_hitl_payload_digest");
    }

    @Test
    void twoConcurrentClaimsProduceExactlyOneWinner() throws Exception {
        RbacContext caller = RbacContext.builder()
                .userId(1001L).role("admin").merchantId(42L).build();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ApprovalExecutionGuard.ExecutionDecision> first = pool.submit(() -> {
                start.await();
                return guard.claim(String.valueOf(APPROVAL_ID), digest, payload, caller);
            });
            Future<ApprovalExecutionGuard.ExecutionDecision> second = pool.submit(() -> {
                start.await();
                return guard.claim(String.valueOf(APPROVAL_ID), digest, payload, caller);
            });
            start.countDown();

            List<ApprovalExecutionGuard.ExecutionStatus> statuses =
                    List.of(first.get().status(), second.get().status());
            assertThat(statuses).containsExactlyInAnyOrder(
                    ApprovalExecutionGuard.ExecutionStatus.CLAIMED,
                    ApprovalExecutionGuard.ExecutionStatus.IN_PROGRESS
            );
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM hitl_approval WHERE id = ?",
                String.class,
                APPROVAL_ID
        )).isEqualTo("EXECUTING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hitl_approval WHERE id = ? AND execution_id IS NOT NULL",
                Integer.class,
                APPROVAL_ID
        )).isEqualTo(1);
    }

    @Test
    void concurrentRefundExecutionsCallServerOnceThenReplayStoredResult() throws Exception {
        LocalLifeInternalClient internalClient = mock(LocalLifeInternalClient.class);
        ExecuteRefundTool refundTool = new ExecuteRefundTool(objectMapper, internalClient, guard);
        ObjectNode arguments = objectMapper.createObjectNode()
                .put("order_id", payload.orderId())
                .put("amount", payload.amountMinor())
                .put("approval_id", String.valueOf(APPROVAL_ID))
                .put("approval_digest", digest)
                .put("reason", payload.reason());
        Map<String, Object> serverResult = Map.of(
                "refund_status", "SUCCESS",
                "refund_id", "REFUND_ONCE"
        );
        CountDownLatch serverEntered = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        when(internalClient.refund(
                payload.orderId(),
                payload.amountMinor(),
                String.valueOf(APPROVAL_ID),
                payload.reason()
        )).thenAnswer(invocation -> {
            serverEntered.countDown();
            assertThat(releaseServer.await(5, TimeUnit.SECONDS)).isTrue();
            return serverResult;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Object> first = pool.submit(() -> executeAsRequester(refundTool, arguments));
        try {
            assertThat(serverEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> second = pool.submit(() -> executeAsRequester(refundTool, arguments));

            assertThatThrownBy(second::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ToolBusinessException.class);
        } finally {
            releaseServer.countDown();
            pool.shutdown();
        }

        assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(serverResult);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM hitl_approval WHERE id = ?",
                String.class,
                APPROVAL_ID
        )).isEqualTo("EXECUTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_result FROM hitl_approval WHERE id = ?",
                String.class,
                APPROVAL_ID
        )).contains("REFUND_ONCE");

        Object replay = executeAsRequester(refundTool, arguments);
        assertThat(replay).isEqualTo(serverResult);
        verify(internalClient, times(1)).refund(
                payload.orderId(),
                payload.amountMinor(),
                String.valueOf(APPROVAL_ID),
                payload.reason()
        );
    }

    @Test
    void expiredLeaseRecoversWithSameDigestAndCompletesForReplay() {
        RbacContext caller = requester();
        ApprovalExecutionGuard.ExecutionDecision first = guard.claim(
                String.valueOf(APPROVAL_ID), digest, payload, caller
        );
        jdbcTemplate.update(
                "UPDATE hitl_approval SET execution_lease_until = UTC_TIMESTAMP() - INTERVAL 1 SECOND WHERE id = ?",
                APPROVAL_ID
        );

        ApprovalExecutionGuard.ExecutionDecision recovered = guard.claim(
                String.valueOf(APPROVAL_ID), digest, payload, caller
        );

        assertThat(recovered.status()).isEqualTo(ApprovalExecutionGuard.ExecutionStatus.CLAIMED);
        assertThat(recovered.claim().executionId()).isNotEqualTo(first.claim().executionId());
        assertThatThrownBy(() -> guard.complete(
                first.claim(),
                Map.of("status", "STALE_WORKER_RESULT")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completion was not accepted");
        guard.complete(recovered.claim(), Map.of("status", "SUCCESS"));
        assertThat(guard.claim(String.valueOf(APPROVAL_ID), digest, payload, caller).status())
                .isEqualTo(ApprovalExecutionGuard.ExecutionStatus.REPLAY);
    }

    private Object executeAsRequester(ExecuteRefundTool refundTool, ObjectNode arguments) {
        RbacContext.set(requester());
        try {
            return refundTool.execute(arguments.deepCopy());
        } finally {
            RbacContext.clear();
        }
    }

    private RbacContext requester() {
        return RbacContext.builder()
                .userId(1001L)
                .role("admin")
                .merchantId(42L)
                .build();
    }

    private static String filesystemLocation(String relativePath) {
        return "filesystem:" + REPOSITORY_ROOT.resolve(relativePath).toAbsolutePath().normalize();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("local-life-server/src/main/resources/db/migration"))
                    && Files.exists(current.resolve("local-life-copilot/src/main/resources/db/migration"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository migration directories");
    }
}
