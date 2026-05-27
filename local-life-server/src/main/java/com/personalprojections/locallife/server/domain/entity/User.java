package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体，对应数据库 user 表。
 *
 * <h2>分层说明：为什么有 Entity / DTO / VO 三种对象</h2>
 * <ul>
 *   <li><b>Entity（实体）</b>：与数据库表一一对应，只用于 Mapper 层的 SQL 操作，
 *       不应该直接暴露给 Controller 返回给前端。
 *       原因：实体包含敏感字段（手机号明文、deleted 标记等），
 *             以及数据库内部字段（created_at、updated_at），不应该全部返回给前端。</li>
 *
 *   <li><b>DTO（Data Transfer Object）</b>：在层与层之间传递数据的对象，
 *       例如 Controller 接收前端请求的参数对象（LoginRequest）。</li>
 *
 *   <li><b>VO（View Object）</b>：返回给前端的响应对象，
 *       只包含前端需要的字段，敏感字段脱敏处理（如手机号 → 138****8000）。</li>
 * </ul>
 *
 * <h2>MyBatis-Plus 注解说明</h2>
 * <ul>
 *   <li>{@code @TableName("user")}  - 映射到 user 表，字段名自动按驼峰转下划线映射</li>
 *   <li>{@code @TableId(type = IdType.ASSIGN_ID)} - 主键使用雪花算法，不依赖数据库自增</li>
 *   <li>{@code @TableField(fill = FieldFill.INSERT)} - 插入时自动填充 createdAt</li>
 *   <li>{@code @TableField(fill = FieldFill.INSERT_UPDATE)} - 插入和更新时自动填充 updatedAt</li>
 *   <li>{@code @TableLogic} - 标识逻辑删除字段，MyBatis-Plus 会自动追加 WHERE deleted = 0</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user")
public class User {

    /**
     * 主键，雪花算法生成的 BIGINT。
     * {@code IdType.ASSIGN_ID}：MyBatis-Plus 在 insert 时自动调用雪花算法生成，
     * 不需要数据库 AUTO_INCREMENT，也不需要手动赋值。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 手机号，登录唯一标识。
     * 数据库列名：mobile（与字段名相同，无需额外注解）。
     * 注意：数据库存明文，Service 层使用时脱敏，不能在日志中打印完整手机号。
     */
    private String mobile;

    /**
     * 用户昵称，初始值由 Service 层生成（"user_" + userId 后 6 位），用户可修改。
     */
    private String nickname;

    /**
     * 头像图片 URL，初始为空字符串，前端展示默认头像。
     */
    private String avatar;

    /**
     * 个人简介，最多 128 个字符，初始为空字符串。
     */
    private String bio;

    /**
     * 账号状态：ENABLED（正常）/ DISABLED（被禁用）。
     * 鉴权拦截器每次请求时读取此字段，DISABLED 立即拒绝。
     */
    private String status;

    /**
     * 逻辑删除标记：0 = 未删除，1 = 已删除。
     * {@code @TableLogic}：MyBatis-Plus 自动处理，
     * 所有 select/update/delete 操作自动追加 WHERE deleted = 0。
     * 调用 removeById() 时执行 UPDATE SET deleted = 1，而不是物理 DELETE。
     */
    @TableLogic
    private Integer deleted;

    /**
     * 记录创建时间，INSERT 时自动填充。
     * {@code FieldFill.INSERT}：只在 INSERT 时填充，UPDATE 时不修改。
     * 需要配合 {@link com.personalprojections.locallife.server.config.MybatisPlusConfig}
     * 中注册的 MetaObjectHandler 才能生效。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间，INSERT 和 UPDATE 时均自动填充。
     * {@code FieldFill.INSERT_UPDATE}：插入时填充当前时间，更新时也更新为当前时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
