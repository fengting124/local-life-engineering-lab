package com.personalprojections.locallife.server.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.domain.mapper.CompensationCouponBindingMapper;
import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import com.personalprojections.locallife.server.domain.mapper.OrderInfoMapper;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.domain.mapper.SideEffectLedgerMapper;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import com.personalprojections.locallife.server.module.internal.CouponTerms;
import com.personalprojections.locallife.server.module.internal.InternalController.CompensateRequest;
import com.personalprojections.locallife.server.module.internal.InternalController.CompensateResult;
import com.personalprojections.locallife.server.module.internal.InternalService;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class CompensationCouponJourneyIntegrationTest {

    private static final Path MIGRATIONS = repositoryRoot()
            .resolve("local-life-server/src/main/resources/db/migration");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("local_life")
            .withUsername("root")
            .withPassword("123456");

    private static HikariDataSource dataSource;
    private static InternalService service;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void setUpPersistence() throws Exception {
        try (Connection connection = connection()) {
            List<Path> scripts;
            try (var paths = Files.list(MIGRATIONS)) {
                scripts = paths
                        .filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
                        .sorted(Comparator.comparingInt(CompensationCouponJourneyIntegrationTest::migrationVersion))
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

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setLogicDeleteField("deleted");
        dbConfig.setLogicDeleteValue("1");
        dbConfig.setLogicNotDeleteValue("0");
        globalConfig.setDbConfig(dbConfig);
        globalConfig.setMetaObjectHandler(new MetaObjectHandler() {
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
        });
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.setTypeAliasesPackage("com.personalprojections.locallife.server.domain.entity");
        SqlSessionFactory sessionFactory = factoryBean.getObject();
        MybatisConfiguration configuration = (MybatisConfiguration) sessionFactory.getConfiguration();
        configuration.addMapper(OrderInfoMapper.class);
        configuration.addMapper(SideEffectLedgerMapper.class);
        configuration.addMapper(ShopMapper.class);
        configuration.addMapper(CompensationCouponBindingMapper.class);
        configuration.addMapper(CouponTemplateMapper.class);
        configuration.addMapper(UserCouponMapper.class);

        SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory);
        service = new InternalService(
                sessions.getMapper(OrderInfoMapper.class),
                sessions.getMapper(SideEffectLedgerMapper.class),
                sessions.getMapper(ShopMapper.class),
                sessions.getMapper(CompensationCouponBindingMapper.class),
                sessions.getMapper(CouponTemplateMapper.class),
                sessions.getMapper(UserCouponMapper.class),
                new ObjectMapper());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void seedJourney() throws Exception {
        execute("DELETE FROM side_effect_ledger");
        execute("DELETE FROM user_coupon");
        execute("DELETE FROM compensation_coupon_binding");
        execute("DELETE FROM order_info");
        execute("DELETE FROM coupon_template");
        execute("DELETE FROM shop");
        execute("""
                INSERT INTO shop(id, merchant_id, shop_name, status)
                VALUES (2001, 3001, 'Compensation Test Shop', 'ONLINE')
                """);
        execute("""
                INSERT INTO order_info(
                    id, order_no, user_id, shop_id, original_amount,
                    coupon_discount, order_amount, order_status, remark, expire_at
                ) VALUES (1001, 'ORDER_1', 5001, 2001, 9900, 0, 9900,
                          'PAID', '', NOW() + INTERVAL 1 DAY)
                """);
        execute("""
                INSERT INTO coupon_template(
                    id, shop_id, coupon_name, discount_type, discount_value,
                    min_order_amount, total_stock, remain_stock, per_user_limit,
                    valid_days, status
                ) VALUES (4001, 2001, '20 yuan compensation', 'CASH', 2000,
                          0, 10, 10, 1, 30, 'ACTIVE')
                """);
        execute("""
                INSERT INTO compensation_coupon_binding(
                    id, shop_id, merchant_id, face_value_minor,
                    coupon_template_id, enabled
                ) VALUES (6001, 2001, 3001, 2000, 4001, 1)
                """);
    }

    @Test
    void successAndRetryProduceOnePersistentEffect() {
        CompensateResult first = issue(validRequest("APPROVAL_1"));
        CompensateResult replay = issue(validRequest("APPROVAL_1"));

        assertThat(first.getCouponId()).isEqualTo(replay.getCouponId());
        assertThat(queryInt("SELECT remain_stock FROM coupon_template WHERE id = 4001")).isEqualTo(9);
        assertThat(queryInt("SELECT COUNT(*) FROM user_coupon")).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM side_effect_ledger WHERE status = 'SUCCESS'")).isEqualTo(1);
        assertThat(queryString("SELECT issuance_key FROM user_coupon"))
                .isEqualTo("COMPENSATION:APPROVAL_1");
    }

    @Test
    void concurrentSameApprovalProducesOnePersistentEffect() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> call = () -> issue(validRequest("APPROVAL_RACE")).getCouponId();
            List<String> couponIds = pool.invokeAll(List.of(call, call)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception error) {
                            throw new AssertionError(error);
                        }
                    })
                    .toList();
            assertThat(couponIds).hasSize(2).allMatch(couponIds.get(0)::equals);
        } finally {
            pool.shutdownNow();
        }
        assertThat(queryInt("SELECT remain_stock FROM coupon_template WHERE id = 4001")).isEqualTo(9);
        assertThat(queryInt("SELECT COUNT(*) FROM user_coupon")).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM side_effect_ledger")).isEqualTo(1);
    }

    @Test
    void stockExhaustionRollsBackLedgerAndCoupon() throws Exception {
        execute("UPDATE coupon_template SET remain_stock = 0 WHERE id = 4001");

        assertThatThrownBy(() -> issue(validRequest("APPROVAL_EMPTY")))
                .isInstanceOf(BizException.class);
        assertThat(queryInt("SELECT COUNT(*) FROM user_coupon")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM side_effect_ledger")).isZero();
    }

    @Test
    void staleTermsDoNotStartSideEffect() {
        CompensateRequest request = validRequest("APPROVAL_STALE");
        request.setCouponValidDays(3);

        assertThatThrownBy(() -> issue(request)).isInstanceOf(BizException.class);
        assertThat(queryInt("SELECT remain_stock FROM coupon_template WHERE id = 4001")).isEqualTo(10);
        assertThat(queryInt("SELECT COUNT(*) FROM user_coupon")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM side_effect_ledger")).isZero();
    }

    @Test
    void separateApprovalsCanIssueSameTemplateTwice() {
        issue(validRequest("APPROVAL_A"));
        issue(validRequest("APPROVAL_B"));

        assertThat(queryInt("SELECT remain_stock FROM coupon_template WHERE id = 4001")).isEqualTo(8);
        assertThat(queryInt("SELECT COUNT(*) FROM user_coupon")).isEqualTo(2);
        assertThat(queryInt("SELECT COUNT(*) FROM side_effect_ledger")).isEqualTo(2);
    }

    private CompensateResult issue(CompensateRequest request) {
        return transaction.execute(status -> service.issueCompensationCoupon("ORDER_1", request));
    }

    private CompensateRequest validRequest(String approvalId) {
        CouponTerms terms = new CouponTerms(1, "4001", "2001", "3001", "CASH", 2000, 0, 30);
        CompensateRequest request = new CompensateRequest();
        request.setUserId("5001");
        request.setShopId("2001");
        request.setMerchantId("3001");
        request.setCompensationAmount(2000);
        request.setCouponTemplateId("4001");
        request.setCouponDiscountType("CASH");
        request.setCouponMinOrderAmount(0);
        request.setCouponValidDays(30);
        request.setCouponTermsDigest(terms.digest());
        request.setApprovalId(approvalId);
        request.setReason("service recovery");
        return request;
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
        try (Connection connection = connection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
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
}
