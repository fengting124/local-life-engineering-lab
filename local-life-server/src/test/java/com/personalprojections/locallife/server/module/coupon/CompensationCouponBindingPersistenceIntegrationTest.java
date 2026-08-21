package com.personalprojections.locallife.server.module.coupon;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.domain.entity.CompensationCouponBindingAudit;
import com.personalprojections.locallife.server.domain.entity.Merchant;
import com.personalprojections.locallife.server.domain.mapper.CompensationCouponBindingAuditMapper;
import com.personalprojections.locallife.server.domain.mapper.CompensationCouponBindingMapper;
import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.module.coupon.dto.CompensationCouponBindingVO;
import com.personalprojections.locallife.server.module.coupon.service.CompensationCouponBindingService;
import com.personalprojections.locallife.server.module.merchant.service.MerchantService;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.MDC;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class CompensationCouponBindingPersistenceIntegrationTest {

    private static final long USER_ID = 1001L;
    private static final long MERCHANT_ID = 3001L;
    private static final long SHOP_ID = 2001L;
    private static final int FACE_VALUE = 2000;

    private static final Path MIGRATIONS = repositoryRoot()
            .resolve("local-life-server/src/main/resources/db/migration");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("local_life")
            .withUsername("root")
            .withPassword("123456");

    private static HikariDataSource dataSource;
    private static TransactionTemplate transaction;
    private static MerchantService merchantService;
    private static ShopMapper shopMapper;
    private static CompensationCouponBindingMapper bindingMapper;
    private static CouponTemplateMapper templateMapper;
    private static CompensationCouponBindingAuditMapper auditMapper;
    private static CompensationCouponBindingService service;

    @BeforeAll
    static void setUpPersistence() throws Exception {
        try (Connection connection = connection()) {
            List<Path> scripts;
            try (var paths = Files.list(MIGRATIONS)) {
                scripts = paths
                        .filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
                        .sorted(Comparator.comparingInt(
                                CompensationCouponBindingPersistenceIntegrationTest::migrationVersion))
                        .toList();
            }
            for (Path script : scripts) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
            }
        }

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        dataSource.setDriverClassName(MYSQL.getDriverClassName());

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        GlobalConfig global = new GlobalConfig();
        GlobalConfig.DbConfig db = new GlobalConfig.DbConfig();
        db.setLogicDeleteField("deleted");
        db.setLogicDeleteValue("1");
        db.setLogicNotDeleteValue("0");
        global.setDbConfig(db);
        global.setMetaObjectHandler(new TestMetaObjectHandler());
        factory.setGlobalConfig(global);
        factory.setTypeAliasesPackage("com.personalprojections.locallife.server.domain.entity");

        SqlSessionFactory sessionFactory = factory.getObject();
        MybatisConfiguration configuration =
                (MybatisConfiguration) sessionFactory.getConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ShopMapper.class);
        configuration.addMapper(CompensationCouponBindingMapper.class);
        configuration.addMapper(CouponTemplateMapper.class);
        configuration.addMapper(CompensationCouponBindingAuditMapper.class);

        SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory);
        shopMapper = sessions.getMapper(ShopMapper.class);
        bindingMapper = sessions.getMapper(CompensationCouponBindingMapper.class);
        templateMapper = sessions.getMapper(CouponTemplateMapper.class);
        auditMapper = sessions.getMapper(CompensationCouponBindingAuditMapper.class);
        merchantService = mock(MerchantService.class);
        when(merchantService.requireApprovedMerchant()).thenReturn(
                Merchant.builder().id(MERCHANT_ID).userId(USER_ID).status("APPROVED").build());

        service = new CompensationCouponBindingService(
                merchantService, shopMapper, bindingMapper, templateMapper,
                auditMapper, new ObjectMapper().findAndRegisterModules());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void seed() throws Exception {
        execute("DELETE FROM compensation_coupon_binding_audit");
        execute("DELETE FROM compensation_coupon_binding");
        execute("DELETE FROM coupon_template");
        execute("DELETE FROM shop");
        execute("""
                INSERT INTO shop(id, merchant_id, shop_name, status)
                VALUES (2001, 3001, 'Binding Shop', 'ONLINE'),
                       (2002, 3001, 'Second Shop', 'ONLINE')
                """);
        execute("""
                INSERT INTO coupon_template(
                    id, shop_id, coupon_name, discount_type, discount_value,
                    min_order_amount, total_stock, remain_stock, per_user_limit,
                    valid_days, status
                ) VALUES
                    (4001, 2001, 'Comp A', 'CASH', 2000, 0, 10, 10, 1, 30, 'ACTIVE'),
                    (4002, 2001, 'Comp B', 'CASH', 2000, 1000, 20, 20, 1, 14, 'ACTIVE'),
                    (4003, 2002, 'Comp C', 'CASH', 2000, 0, 10, 10, 1, 30, 'ACTIVE')
                """);
    }

    @Test
    void stateTransitionsAreAtomicAuditedAndIdempotent() {
        inTransaction(() -> service.upsert(SHOP_ID, FACE_VALUE, 4001L));
        String bindingId = queryString(
                "SELECT id FROM compensation_coupon_binding WHERE shop_id = 2001");

        inTransaction(() -> service.upsert(SHOP_ID, FACE_VALUE, 4001L));
        inTransaction(() -> service.upsert(SHOP_ID, FACE_VALUE, 4002L));
        inTransaction(() -> service.disable(SHOP_ID, FACE_VALUE));
        inTransaction(() -> service.disable(SHOP_ID, FACE_VALUE));
        inTransaction(() -> service.upsert(SHOP_ID, FACE_VALUE, 4002L));

        assertThat(queryInt("SELECT COUNT(*) FROM compensation_coupon_binding")).isEqualTo(1);
        assertThat(queryString(
                "SELECT id FROM compensation_coupon_binding WHERE shop_id = 2001"))
                .isEqualTo(bindingId);
        assertThat(queryLong("""
                SELECT coupon_template_id FROM compensation_coupon_binding
                WHERE shop_id = 2001
                """)).isEqualTo(4002L);
        assertThat(queryInt("""
                SELECT enabled FROM compensation_coupon_binding WHERE shop_id = 2001
                """)).isEqualTo(1);
        assertThat(queryString("""
                SELECT GROUP_CONCAT(action ORDER BY id SEPARATOR ',')
                FROM compensation_coupon_binding_audit
                """)).isEqualTo("CREATE,REPLACE,DISABLE,ENABLE");
        assertThat(queryInt("SELECT COUNT(*) FROM compensation_coupon_binding_audit"))
                .isEqualTo(4);
    }

    @Test
    void concurrentWritesForOneShopSerializeWithoutDuplicateRows() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<CompensationCouponBindingVO> first =
                    () -> inTransaction(() -> service.upsert(SHOP_ID, FACE_VALUE, 4001L));
            Callable<CompensationCouponBindingVO> second =
                    () -> inTransaction(() -> service.upsert(SHOP_ID, FACE_VALUE, 4002L));

            List<CompensationCouponBindingVO> results =
                    pool.invokeAll(List.of(first, second)).stream()
                            .map(future -> {
                                try {
                                    return future.get();
                                } catch (Exception error) {
                                    throw new AssertionError(error);
                                }
                            })
                            .toList();

            assertThat(results).hasSize(2);
        } finally {
            pool.shutdownNow();
        }

        assertThat(queryInt("SELECT COUNT(*) FROM compensation_coupon_binding")).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM compensation_coupon_binding_audit"))
                .isEqualTo(2);
        assertThat(queryString("""
                SELECT GROUP_CONCAT(action ORDER BY id SEPARATOR ',')
                FROM compensation_coupon_binding_audit
                """)).isEqualTo("CREATE,REPLACE");
    }

    @Test
    void auditFailureRollsBackBindingMutation() {
        CompensationCouponBindingAuditMapper failingAudit =
                mock(CompensationCouponBindingAuditMapper.class);
        when(failingAudit.insert(any(CompensationCouponBindingAudit.class)))
                .thenThrow(new IllegalStateException("audit unavailable"));
        CompensationCouponBindingService failingService =
                new CompensationCouponBindingService(
                        merchantService, shopMapper, bindingMapper, templateMapper,
                        failingAudit, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> inTransaction(
                () -> failingService.upsert(SHOP_ID, FACE_VALUE, 4001L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit unavailable");

        assertThat(queryInt("SELECT COUNT(*) FROM compensation_coupon_binding")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM compensation_coupon_binding_audit")).isZero();
    }

    @Test
    void invalidHistoricalBindingRemainsVisible() throws Exception {
        execute("""
                INSERT INTO compensation_coupon_binding(
                    id, shop_id, merchant_id, face_value_minor,
                    coupon_template_id, enabled
                ) VALUES (6001, 2001, 3001, 2000, 4001, 1)
                """);
        execute("UPDATE coupon_template SET status = 'INACTIVE' WHERE id = 4001");

        List<CompensationCouponBindingVO> result = withUser(() -> service.list(SHOP_ID));

        assertThat(result).singleElement()
                .extracting(CompensationCouponBindingVO::getConfigurationStatus)
                .isEqualTo("TEMPLATE_INVALID");
    }

    private static <T> T inTransaction(Supplier<T> action) {
        return transaction.execute(status -> withUser(action));
    }

    private static <T> T withUser(Supplier<T> action) {
        UserContext.set(LoginUserDTO.builder().userId(USER_ID).status("ENABLED").build());
        MDC.put("requestId", "mysql-journey");
        try {
            return action.get();
        } finally {
            UserContext.clear();
            MDC.clear();
        }
    }

    private static int migrationVersion(Path path) {
        String filename = path.getFileName().toString();
        return Integer.parseInt(filename.substring(1, filename.indexOf("__")));
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cursor != null
                && !Files.isDirectory(cursor.resolve("local-life-server/src/main/resources/db/migration"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IllegalStateException("repository root not found");
        }
        return cursor;
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int queryInt(String sql) {
        return Math.toIntExact(queryLong(sql));
    }

    private static long queryLong(String sql) {
        try (Connection connection = connection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static String queryString(String sql) {
        try (Connection connection = connection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static final class TestMetaObjectHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            LocalDateTime now = LocalDateTime.now();
            strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
            strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        }
    }
}
