package com.personalprojections.locallife.server.integration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class CompensationCouponMigrationIntegrationTest {

    private static final Path MIGRATIONS = repositoryRoot()
            .resolve("local-life-server/src/main/resources/db/migration");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("local_life")
            .withUsername("root")
            .withPassword("123456");

    @Test
    void v14MigratesLegacyRowsAndPreservesIssuanceIdentities() throws Exception {
        try (Connection connection = connection()) {
            applyMigrationsThrough(connection, 13);
            insertLegacyCoupon(connection);

            applyMigration(connection, "V14__add_compensation_coupon_binding.sql");

            assertThat(queryString(connection,
                    "SELECT source_type FROM user_coupon WHERE id = 9001"))
                    .isEqualTo("SECKILL");
            assertThat(queryString(connection,
                    "SELECT issuance_key FROM user_coupon WHERE id = 9001"))
                    .isEqualTo("SECKILL:5001:6001");
            assertThat(columnNullable(connection, "user_coupon", "seckill_session_id"))
                    .isEqualTo("YES");
            assertThat(indexNames(connection, "compensation_coupon_binding"))
                    .contains("uk_comp_binding_shop_face", "uk_comp_binding_shop_template");
            assertThat(indexNames(connection, "user_coupon"))
                    .contains("uk_user_coupon_issuance", "uk_user_coupon_source_approval")
                    .doesNotContain("uk_user_coupon_template");

            insertSeckillCoupon(connection, 9002, 5002, 6002, 7002);
            assertThatThrownBy(() -> insertSeckillCoupon(connection, 9003, 5002, 6002, 7003))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Duplicate entry");

            insertCompensationCoupon(connection, 9004, 5003, 6003, "APPROVAL-1");
            insertCompensationCoupon(connection, 9005, 5003, 6003, "APPROVAL-2");
            assertThat(queryInt(connection, """
                    SELECT COUNT(*) FROM user_coupon
                    WHERE user_id = 5003 AND coupon_template_id = 6003
                    """)).isEqualTo(2);
        }
    }

    private static void insertLegacyCoupon(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO user_coupon(
                    id, user_id, coupon_template_id, seckill_session_id,
                    coupon_status, received_at, expire_at
                ) VALUES (9001, 5001, 6001, 7001, 'UNUSED', NOW(), NOW() + INTERVAL 7 DAY)
                """);
    }

    private static void insertSeckillCoupon(
            Connection connection, long id, long userId, long templateId, long sessionId
    ) throws SQLException {
        execute(connection, """
                INSERT INTO user_coupon(
                    id, user_id, coupon_template_id, seckill_session_id,
                    coupon_status, received_at, expire_at,
                    source_type, source_approval_id, issuance_key
                ) VALUES (?, ?, ?, ?, 'UNUSED', NOW(), NOW() + INTERVAL 7 DAY,
                          'SECKILL', NULL, ?)
                """, id, userId, templateId, sessionId,
                "SECKILL:" + userId + ":" + templateId);
    }

    private static void insertCompensationCoupon(
            Connection connection, long id, long userId, long templateId, String approvalId
    ) throws SQLException {
        execute(connection, """
                INSERT INTO user_coupon(
                    id, user_id, coupon_template_id, seckill_session_id,
                    coupon_status, received_at, expire_at,
                    source_type, source_approval_id, issuance_key
                ) VALUES (?, ?, ?, NULL, 'UNUSED', NOW(), NOW() + INTERVAL 30 DAY,
                          'COMPENSATION', ?, ?)
                """, id, userId, templateId, approvalId, "COMPENSATION:" + approvalId);
    }

    private static void applyMigrationsThrough(Connection connection, int maxVersion) throws Exception {
        List<Path> scripts;
        try (var paths = Files.list(MIGRATIONS)) {
            scripts = paths
                    .filter(path -> migrationVersion(path) <= maxVersion)
                    .sorted(Comparator.comparingInt(CompensationCouponMigrationIntegrationTest::migrationVersion))
                    .toList();
        }
        for (Path script : scripts) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
        }
    }

    private static void applyMigration(Connection connection, String filename) {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource(MIGRATIONS.resolve(filename)));
    }

    private static int migrationVersion(Path path) {
        String filename = path.getFileName().toString();
        if (!filename.matches("V\\d+__.*\\.sql")) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(filename.substring(1, filename.indexOf("__")));
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            statement.executeUpdate();
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static String columnNullable(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT IS_NULLABLE FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private static List<String> indexNames(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT DISTINCT INDEX_NAME FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                var names = new java.util.ArrayList<String>();
                while (result.next()) {
                    names.add(result.getString(1));
                }
                return names;
            }
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("local-life-server/src/main/resources/db/migration"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository root");
    }
}
