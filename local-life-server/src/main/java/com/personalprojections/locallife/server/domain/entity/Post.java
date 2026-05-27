package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 探店笔记实体，对应数据库表 {@code post}。
 *
 * <h2>核心业务规则</h2>
 * <ul>
 *   <li>每篇笔记必须关联一个 ONLINE 状态的门店（Service 层校验，不在此处）</li>
 *   <li>发布后立即可见（status = PUBLISHED），当前阶段不实现审核流程</li>
 *   <li>删除使用逻辑删除（deleted = 1），不物理删除，保留数据用于分析</li>
 *   <li>点赞数和评论数是快照值，实时数据在 Redis，定期同步到此字段</li>
 * </ul>
 *
 * <h2>images 字段设计</h2>
 * <p>图片列表存储为 JSON 数组字符串，例如：
 * {@code ["https://cdn.example.com/img1.jpg","https://cdn.example.com/img2.jpg"]}
 * <p>当前阶段用字符串字段存储，不引入 JSON 类型列，避免不同 MySQL 版本的兼容性问题。
 * 序列化/反序列化由 Service 层负责（Jackson ObjectMapper）。
 * 后续如接入 OSS，直接替换 URL，不影响字段结构。
 *
 * <h2>MyBatis-Plus 注解说明</h2>
 * <ul>
 *   <li>{@code @TableId(ASSIGN_ID)} — 雪花算法，应用层生成，不依赖数据库自增</li>
 *   <li>{@code @TableLogic} — 开启逻辑删除，查询时自动追加 {@code AND deleted = 0}</li>
 *   <li>{@code @TableField(fill = INSERT)} — 插入时自动填充，由 {@code MetaObjectHandler} 执行</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("post")
public class Post {

    /**
     * 笔记 ID，雪花算法生成，全局唯一。
     * ASSIGN_ID 策略：MyBatis-Plus 在 insert 时调用 IdentifierGenerator 生成，
     * 不需要数据库 AUTO_INCREMENT，适合分布式场景。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 作者用户 ID，逻辑外键 → user.id。
     * 不加数据库级外键约束（分布式场景下外键约束影响性能且不适用于分库分表）。
     */
    private Long userId;

    /**
     * 关联门店 ID，逻辑外键 → shop.id。
     * Service 层会校验门店是否存在且为 ONLINE 状态，此处只存 ID。
     */
    private Long shopId;

    /** 笔记标题，最多 128 字符，可以为空字符串（允许无标题笔记）。 */
    private String title;

    /**
     * 笔记正文，TEXT 类型，不限制长度（数据库层面）。
     * 当前阶段存纯文本，后续可改为 Markdown 或富文本格式，字段类型不变。
     */
    private String content;

    /**
     * 图片 URL 列表，存储为 JSON 数组字符串。
     * 示例：{@code ["https://cdn.example.com/a.jpg","https://cdn.example.com/b.jpg"]}
     * 空时存 "[]"，不存 null，避免前端 null 判断。
     * 最多 9 张（业务限制，在 Service 层校验）。
     */
    private String images;

    /**
     * 点赞数快照，定期从 Redis 同步（非实时）。
     * 实时数存 Redis Key：{@code post:like:count:{postId}}
     * 此字段用于 Redis 故障后的兜底展示，以及数据分析（冷数据不在 Redis）。
     */
    private Integer likeCount;

    /**
     * 评论数快照，与 likeCount 同样是「快照」机制。
     * 实时评论数 = 数据库 COUNT，由于评论不走 Redis，此字段每次新增评论时 +1 更新。
     */
    private Integer commentCount;

    /**
     * 笔记状态：DRAFT（草稿，预留）/ PUBLISHED（已发布）。
     * 当前阶段发布接口直接设为 PUBLISHED，DRAFT 状态预留给后续草稿功能。
     */
    private String status;

    /**
     * 逻辑删除标志：0 正常 / 1 已删除。
     * {@code @TableLogic} 使 MyBatis-Plus 在所有 selectXxx 方法中自动追加
     * {@code AND deleted = 0} 过滤条件，查不到已删除的数据。
     * deleteById 方法会改写为 {@code UPDATE post SET deleted = 1 WHERE id = ?}。
     */
    @TableLogic
    private Integer deleted;

    /**
     * 创建时间（发布时间），INSERT 时由 MetaObjectHandler 自动填充。
     * 不接受客户端传入，防止时间篡改。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 最后更新时间，INSERT 和 UPDATE 时均自动填充。
     * 可用于排查「笔记何时被修改」（当前阶段发布后不支持修改正文）。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
