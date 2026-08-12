package com.personalprojections.locallife.copilot.domain.mapper;

import com.personalprojections.locallife.copilot.domain.dto.ShopMetricsSnapshot;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ShopMetricsRangeContractIntegrationTest {

    private static final long USER_ID = 911000000001L;
    private static final long MERCHANT_ID = 921000000001L;
    private static final long SHOP_ID = 931000000001L;
    private static final Path REPOSITORY_ROOT = findRepositoryRoot();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("local_life")
            .withUsername("root")
            .withPassword("123456");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("hitl.payload-signing.secret", () -> "test-only-hitl-key");
        registry.add("spring.flyway.locations", () -> String.join(",",
                filesystemLocation("local-life-server/src/main/resources/db/migration"),
                filesystemLocation("local-life-copilot/src/main/resources/db/migration")));
    }

    @Autowired
    private CopilotOrderMapper orderMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedRangeBoundaries() {
        jdbcTemplate.update("DELETE FROM order_info WHERE shop_id = ?", SHOP_ID);
        jdbcTemplate.update("DELETE FROM shop WHERE id = ?", SHOP_ID);
        jdbcTemplate.update("DELETE FROM merchant WHERE id = ?", MERCHANT_ID);
        jdbcTemplate.update("DELETE FROM user WHERE id = ?", USER_ID);

        jdbcTemplate.update("INSERT INTO user(id, mobile, nickname) VALUES (?, '19900000002', 'metrics-contract-user')", USER_ID);
        jdbcTemplate.update("INSERT INTO merchant(id, user_id, merchant_name, status) VALUES (?, ?, 'Metrics Merchant', 'APPROVED')", MERCHANT_ID, USER_ID);
        jdbcTemplate.update("INSERT INTO shop(id, merchant_id, shop_name, status) VALUES (?, ?, 'Metrics Shop', 'ONLINE')", SHOP_ID, MERCHANT_ID);

        insertOrder(971000000001L, "202608010001", 1000, "2026-08-01 00:00:00");
        insertOrder(971000000002L, "202608110001", 2000, "2026-08-11 23:59:59");
        insertOrder(971000000003L, "202607310001", 4000, "2026-07-31 23:59:59");
        insertOrder(971000000004L, "202608120001", 8000, "2026-08-12 00:00:00");
    }

    @Test
    void inclusiveRangeAggregatesBothBoundariesAndExcludesAdjacentDays() {
        ShopMetricsSnapshot metrics = orderMapper.selectShopMetrics(
                MERCHANT_ID, "2026-08-01", "2026-08-11", null);

        assertThat(metrics.getOrderCount()).isEqualTo(2L);
        assertThat(metrics.getGmv()).isEqualTo(3000L);
    }

    private void insertOrder(long id, String orderNo, int amount, String createdAt) {
        jdbcTemplate.update("""
                INSERT INTO order_info(
                    id, order_no, user_id, shop_id, original_amount,
                    coupon_discount, order_amount, order_status, expire_at, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, ?, 'PAID', '2027-01-01 00:00:00', ?)
                """, id, orderNo, USER_ID, SHOP_ID, amount, amount, createdAt);
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
