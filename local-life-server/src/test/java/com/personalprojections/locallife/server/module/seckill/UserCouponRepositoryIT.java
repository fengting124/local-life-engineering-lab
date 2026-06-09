package com.personalprojections.locallife.server.module.seckill;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * user_coupon 仓储层集成测试 —— 用<b>真实 MySQL 8.4</b>（Testcontainers）验证
 * 单元测试摸不到的「数据库真实行为」。
 *
 * <h2>为什么必须用真实 MySQL，不能用 Mock / H2</h2>
 * <ul>
 *   <li><b>唯一索引冲突</b>：{@code uk_user_coupon_template (user_id, coupon_template_id)}
 *       是「一人一单」的最后一道防线——即使 Redis 判重整个失效，DB 也必须挡住重复领取。
 *       这个行为只有真 MySQL 才能验证：Mock 不会抛冲突，H2 的唯一约束语义与 MySQL 有差异。</li>
 *   <li><b>逻辑删除</b>：MyBatis-Plus 的 {@code @TableLogic} 把 DELETE 改写成
 *       {@code UPDATE ... SET deleted=1}，查询自动追加 {@code WHERE deleted=0}。
 *       要确认改写后的 SQL 在真 MySQL 上行为正确，得真跑一遍。</li>
 * </ul>
 *
 * <h2>为什么手动拼 MyBatis-Plus，而不用 @SpringBootTest</h2>
 * <p>本服务的完整 Spring 上下文还会拉起 Elasticsearch / RocketMQ / ShardingSphere /
 * Canal 等一堆中间件，全 @SpringBootTest 既慢又脆。这里只手动装配
 * {@code DataSource → MybatisSqlSessionFactoryBean → UserCouponMapper} 这一条最小链路，
 * 聚焦仓储层，启动快、不依赖无关组件——这也是「集成测试要尽量窄」的工程取舍。
 */
@Testcontainers
@DisplayName("user_coupon 仓储集成测试（真实 MySQL 8.4）")
class UserCouponRepositoryIT {

    /**
     * 真实 MySQL 8.4 容器，与 infra/docker-compose.dev.yml 生产同款。
     * withInitScript：容器启动后自动执行建表 DDL（user_coupon 表 + 唯一索引）。
     */
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("local_life_it")
            .withInitScript("db/it/user_coupon_schema.sql");

    private static HikariDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void initPersistence() throws Exception {
        // 1. Hikari 连真实容器（用容器随机映射出来的 host:port）
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        dataSource.setDriverClassName(MYSQL.getDriverClassName());

        // 2. 手动装配 MyBatis-Plus 的 SqlSessionFactory（最小可用配置）
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);

        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        // 与 application.yml 的逻辑删除配置保持一致
        dbConfig.setLogicDeleteField("deleted");
        dbConfig.setLogicDeleteValue("1");
        dbConfig.setLogicNotDeleteValue("0");
        globalConfig.setDbConfig(dbConfig);
        // 与生产 MybatisPlusConfig 一致的自动填充处理器：INSERT 时补 createdAt / updatedAt。
        // 实体上这两个字段标了 @TableField(fill=...)，不注册 handler 就会以 NULL 写入而违反 NOT NULL。
        globalConfig.setMetaObjectHandler(new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        });
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.setTypeAliasesPackage("com.personalprojections.locallife.server.domain.entity");

        sqlSessionFactory = factoryBean.getObject();
        // 注册 Mapper：触发 MyBatis-Plus 的 SqlInjector 给 BaseMapper 注入 CRUD，
        // 并初始化 UserCoupon 的 TableInfo（字段 → 列名映射）
        sqlSessionFactory.getConfiguration().addMapper(UserCouponMapper.class);
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        // 每个测试前物理清表，保证隔离（直接 JDBC DELETE，绕过逻辑删除过滤）
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM user_coupon");
        } catch (Exception e) {
            throw new IllegalStateException("清表失败", e);
        }
    }

    // =========================================================
    // CRUD 往返
    // =========================================================

    @Test
    @DisplayName("insert 后能 select 回来，字段往返一致")
    void insert_thenSelect_roundTrips() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserCouponMapper mapper = session.getMapper(UserCouponMapper.class);

            UserCoupon coupon = newCoupon(1001L, 5001L, 6001L, 7001L);
            int rows = mapper.insert(coupon);
            assertThat(rows).isEqualTo(1);

            UserCoupon loaded = mapper.selectOne(
                    new LambdaQueryWrapper<UserCoupon>()
                            .eq(UserCoupon::getUserId, 5001L)
                            .eq(UserCoupon::getCouponTemplateId, 6001L));

            assertThat(loaded).isNotNull();
            assertThat(loaded.getSeckillSessionId()).isEqualTo(7001L);
            assertThat(loaded.getCouponStatus()).isEqualTo("UNUSED");
            // created_at / updated_at 走 DB 的 DEFAULT CURRENT_TIMESTAMP（NOT_NULL 策略下被 MyBatis-Plus 省略）
            assertThat(loaded.getCreatedAt()).isNotNull();
        }
    }

    // =========================================================
    // 核心：唯一索引兜底「一人一单」
    // =========================================================

    @Test
    @DisplayName("同一 (user_id, coupon_template_id) 二次插入：唯一索引抛冲突")
    void uniqueIndex_blocksDoubleClaim_forSameUserAndTemplate() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserCouponMapper mapper = session.getMapper(UserCouponMapper.class);

            // 第一次领取：成功
            mapper.insert(newCoupon(2001L, 8888L, 9999L, 7001L));

            // 第二次「重复领取」：同一用户 + 同一券模板，但不同主键 id
            UserCoupon duplicate = newCoupon(2002L, 8888L, 9999L, 7002L);

            // 唯一索引 uk_user_coupon_template 触发冲突，MyBatis 包装成 PersistenceException，
            // 根因是 MySQL 的 SQLIntegrityConstraintViolationException（Duplicate entry）
            assertThatThrownBy(() -> mapper.insert(duplicate))
                    .isInstanceOf(PersistenceException.class)
                    .hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class)
                    .hasMessageContaining("Duplicate entry");
        }

        // 验证：库里始终只有 1 条（重复的那条没进去）
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserCouponMapper mapper = session.getMapper(UserCouponMapper.class);
            Long count = mapper.selectCount(
                    new LambdaQueryWrapper<UserCoupon>()
                            .eq(UserCoupon::getUserId, 8888L)
                            .eq(UserCoupon::getCouponTemplateId, 9999L));
            assertThat(count).isEqualTo(1L);
        }
    }

    // =========================================================
    // 逻辑删除
    // =========================================================

    @Test
    @DisplayName("逻辑删除后：常规 select 查不到，但行物理仍在")
    void logicDelete_hidesRowFromSelect_butKeepsItPhysically() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            UserCouponMapper mapper = session.getMapper(UserCouponMapper.class);

            UserCoupon coupon = newCoupon(3001L, 1234L, 4321L, 7001L);
            mapper.insert(coupon);

            // 逻辑删除：MyBatis-Plus 改写成 UPDATE ... SET deleted=1
            mapper.deleteById(3001L);

            // 常规查询自动追加 WHERE deleted=0 → 查不到
            UserCoupon afterDelete = mapper.selectById(3001L);
            assertThat(afterDelete).as("逻辑删除后常规查询应为 null").isNull();
        }

        // 但物理行还在（直接 JDBC 绕过逻辑删除过滤验证）
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT deleted FROM user_coupon WHERE id = 3001")) {
            assertThat(rs.next()).as("物理行应仍然存在").isTrue();
            assertThat(rs.getInt("deleted")).as("deleted 标志应被置为 1").isEqualTo(1);
        } catch (Exception e) {
            throw new IllegalStateException("校验物理行失败", e);
        }
    }

    // =========================================================
    // 辅助
    // =========================================================

    private UserCoupon newCoupon(long id, long userId, long templateId, long sessionId) {
        return UserCoupon.builder()
                .id(id)
                .userId(userId)
                .couponTemplateId(templateId)
                .seckillSessionId(sessionId)
                .couponStatus("UNUSED")
                .receivedAt(LocalDateTime.now())
                .expireAt(LocalDateTime.now().plusDays(7))
                .build();
    }
}
