package com.personalprojections.locallife.server.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置类。
 *
 * <h2>包含两部分配置</h2>
 * <ol>
 *   <li><b>分页插件（PaginationInnerInterceptor）</b>：
 *       MyBatis-Plus 的分页查询（IPage）需要注册此插件才能正确生成 LIMIT 语句。
 *       不注册的话，分页查询会全量返回所有数据，没有分页效果。</li>
 *
 *   <li><b>自动填充处理器（MetaObjectHandler）</b>：
 *       配合 {@code @TableField(fill = FieldFill.INSERT)} 注解，
 *       在 INSERT / UPDATE 时自动填充 createdAt 和 updatedAt，
 *       业务代码不需要手动 setCreatedAt(LocalDateTime.now())。</li>
 * </ol>
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 插件。
     *
     * <p>{@link MybatisPlusInterceptor} 是 MyBatis-Plus 的插件总入口，
     * 所有插件都通过 {@code addInnerInterceptor} 方法注册进来。
     *
     * <p>{@link PaginationInnerInterceptor}：
     * <ul>
     *   <li>拦截 SELECT 语句，在 WHERE 后面追加 LIMIT offset, size</li>
     *   <li>自动生成 COUNT(*) 查询统计总数</li>
     *   <li>需要指定数据库类型（DbType.MYSQL），否则可能生成错误的分页 SQL</li>
     * </ul>
     *
     * @return 配置好分页插件的 MybatisPlusInterceptor
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件：指定 MySQL 数据库类型
        interceptor.addInnerInterceptor(
                new PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL)
        );
        return interceptor;
    }

    /**
     * 自动填充处理器，处理 {@code @TableField(fill = ...)} 注解的字段。
     *
     * <p>当 MyBatis-Plus 执行 INSERT 或 UPDATE 时，会调用此处理器，
     * 根据字段的 {@code fill} 策略自动填充值，无需在业务代码中手动赋值。
     *
     * <p>工作原理：
     * <ol>
     *   <li>MyBatis-Plus 在执行 SQL 前，通过反射扫描实体类中标注了 @TableField(fill=...) 的字段</li>
     *   <li>调用 insertFill() 或 updateFill() 方法</li>
     *   <li>通过 setFieldValByName() 为对应字段设置值</li>
     * </ol>
     *
     * @return 自动填充处理器 Bean
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {

            /**
             * INSERT 时触发，填充 createdAt 和 updatedAt。
             *
             * @param metaObject 当前操作的实体对象（通过反射访问字段）
             */
            @Override
            public void insertFill(MetaObject metaObject) {
                log.debug("自动填充 createdAt / updatedAt [INSERT]");
                LocalDateTime now = LocalDateTime.now();
                // 填充 createdAt：对应实体字段名（Java 驼峰命名，不是数据库列名）
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                // 填充 updatedAt
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            /**
             * UPDATE 时触发，只填充 updatedAt（createdAt 不应该被修改）。
             *
             * @param metaObject 当前操作的实体对象
             */
            @Override
            public void updateFill(MetaObject metaObject) {
                log.debug("自动填充 updatedAt [UPDATE]");
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
