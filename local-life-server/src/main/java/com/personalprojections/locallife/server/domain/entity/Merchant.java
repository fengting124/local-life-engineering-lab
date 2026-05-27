package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 商家实体，对应数据库 merchant 表。
 *
 * <h2>商家与用户的关系</h2>
 * <p>商家（Merchant）不是独立角色，而是用户（User）的一种「身份扩展」。
 * 用户先注册（进 user 表），再申请成为商家（进 merchant 表，关联 userId）。
 * 一个用户只能有一个商家身份（uk_merchant_user_id 唯一索引）。
 *
 * <h2>商家状态流转</h2>
 * <pre>
 *   申请提交
 *      ↓
 *   PENDING（待审核）
 *      ↓
 *   APPROVED（审核通过）← 运营人工审核
 *   REJECTED（审核拒绝）← 审核不通过，可重新申请
 *      ↓（违规）
 *   DISABLED（已禁用）  ← 平台强制禁用，无法运营
 * </pre>
 *
 * <h2>面试常见追问</h2>
 * <p>为什么不把商家字段直接合并到 user 表？
 * <ul>
 *   <li>关注点分离：用户信息（昵称、头像、bio）和商家信息（商家名、营业执照、联系方式）
 *       是完全不同的两类数据，合并后 user 表会膨胀且职责不清。</li>
 *   <li>大多数用户不是商家：合并后每个用户记录都要存商家字段，大量 NULL 值浪费空间。</li>
 *   <li>扩展性：商家表可以独立加字段（营业执照、资质图片、审核记录），不影响用户表。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("merchant")
public class Merchant {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 关联的用户 ID，对应 user.id。
     * 数据库有唯一索引 uk_merchant_user_id，一个用户只能绑定一个商家。
     */
    private Long userId;

    /** 商家名称，对外展示，如「小明餐饮有限公司」。 */
    private String merchantName;

    /** 商家 logo URL，前端展示用。 */
    private String logo;

    /** 商家简介，最多 256 字符。 */
    private String description;

    /**
     * 运营联系手机号，商家运营人员的联系方式。
     * 与 user.mobile 可以不同（法人账号 vs 运营账号场景）。
     */
    private String contactMobile;

    /**
     * 商家状态：PENDING / APPROVED / REJECTED / DISABLED。
     * 只有 APPROVED 状态的商家才能创建门店、发布活动。
     */
    private String status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
