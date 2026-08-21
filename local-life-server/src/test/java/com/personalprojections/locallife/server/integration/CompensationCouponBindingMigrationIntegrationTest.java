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
class CompensationCouponBindingMigrationIntegrationTest {

    private static final Path MIGRATIONS = repositoryRoot()
            .resolve("local-life-server/src/main/resources/db/migration");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("local_life")
            .withUsername("root")
            .withPassword("123456");

    @Test
    void v15AddsConstrainedJsonAuditHistory() throws Exception {
        try (Connection connection = connection()) {
            applyMigrationsThrough(connection, 14);
            assertThat(tableExists(connection, "compensation_coupon_binding_audit")).isFalse();

            applyMigration(connection, "V15__add_compensation_coupon_binding_audit.sql");

            assertThat(tableExists(connection, "compensation_coupon_binding_audit")).isTrue();
            assertThat(columnType(connection, "compensation_coupon_binding_audit", "before_snapshot"))
                    .isEqualTo("json");
            assertThat(columnType(connection, "compensation_coupon_binding_audit", "after_snapshot"))
                    .isEqualTo("json");
            assertThat(indexNames(connection, "compensation_coupon_binding_audit"))
                    .contains("idx_comp_binding_audit_shop_created",
                            "idx_comp_binding_audit_binding_created");

            execute(connection, """
                    INSERT INTO compensation_coupon_binding_audit(
                        id, binding_id, shop_id, merchant_id, face_value_minor,
                        action, operator_user_id, request_id,
                        before_snapshot, after_snapshot
                    ) VALUES (
                        1, 10, 20, 30, 2000, 'CREATE', 40, 'request-1',
                        NULL, JSON_OBJECT('couponTemplateId', '50', 'enabled', true)
                    )
                    """);
            assertThat(queryString(connection, """
                    SELECT JSON_UNQUOTE(JSON_EXTRACT(after_snapshot, '$.couponTemplateId'))
                    FROM compensation_coupon_binding_audit WHERE id = 1
                    """)).isEqualTo("50");

            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO compensation_coupon_binding_audit(
                        id, binding_id, shop_id, merchant_id, face_value_minor,
                        action, operator_user_id, request_id, after_snapshot
                    ) VALUES (2, 10, 20, 30, 2000, 'DELETE', 40, 'request-2', JSON_OBJECT())
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_comp_binding_audit_action");
        }
    }

    private static void applyMigrationsThrough(Connection connection, int maxVersion) throws Exception {
        List<Path> scripts;
        try (var paths = Files.list(MIGRATIONS)) {
            scripts = paths
                    .filter(path -> migrationVersion(path) <= maxVersion)
                    .sorted(Comparator.comparingInt(
                            CompensationCouponBindingMigrationIntegrationTest::migrationVersion))
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

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private static String columnType(Connection connection, String table, String column)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT DATA_TYPE FROM information_schema.columns
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

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
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

