package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 门店实体，对应数据库 shop 表。
 *
 * <h2>门店与商家的关系</h2>
 * <p>一个商家（Merchant）可以拥有多家门店（Shop），这是 1:N 关系。
 * 例如：「小明餐饮」商家旗下有「西湖店」「滨江店」「余杭店」三家门店。
 * 门店通过 merchantId 关联到商家。
 *
 * <h2>门店状态流转</h2>
 * <pre>
 *   创建后
 *      ↓
 *   DRAFT（草稿）     ← 商家填写门店信息，尚未提交
 *      ↓（提交上线申请）
 *   ONLINE（已上线）  ← 审核通过，C 端用户可以看到
 *      ↓（商家主动下线 / 平台下线）
 *   OFFLINE（已下线） ← C 端用户看不到，可以恢复上线
 *      ↓（永久关闭）
 *   CLOSED（已关闭）  ← 终态，不可恢复
 * </pre>
 *
 * <h2>地理位置字段说明</h2>
 * <p>longitude（经度）和 latitude（纬度）使用 BigDecimal 对应数据库 DECIMAL(10,7)。
 * 当前阶段 MySQL 只做主数据存储，地理位置搜索（「附近 3km 的门店」）
 * 由后续接入的 Elasticsearch Geo Query 承载。
 * 数据同步链路：MySQL → Canal → MQ → ES 索引消费者（第 5-6 周增强项）。
 *
 * <h2>score 字段说明</h2>
 * <p>综合评分是冗余计算字段：由笔记、评论、订单完成率等综合计算得出，
 * 定期由后台任务更新到 shop.score，用于门店搜索结果排序。
 * 不实时计算的原因：评分计算较重，实时计算会影响写入性能。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("shop")
public class Shop {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属商家 ID，对应 merchant.id。
     * 鉴权层面：商家操作门店时，Service 需要校验 shop.merchantId == 当前登录商家的 merchantId，
     * 防止商家 A 修改商家 B 的门店（越权操作）。
     */
    private Long merchantId;

    /** 门店名称，如「小明饺子馆（西湖店）」。 */
    private String shopName;

    /**
     * 门店分类 ID，1=餐饮 2=酒店 3=娱乐等。
     * 分类表（category）后续迭代再建，第一阶段用整型占位。
     */
    private Integer categoryId;

    /** 门店封面图 URL。 */
    private String coverImage;

    /** 门店简介，最多 512 字符。 */
    private String description;

    /** 详细地址，如「浙江省杭州市西湖区文三路 138 号」。 */
    private String address;

    /**
     * 经度，范围 -180.0 ~ 180.0，精度 7 位小数。
     * BigDecimal 对应 DECIMAL(10,7)，避免浮点数精度问题（double 会有精度误差）。
     */
    private BigDecimal longitude;

    /**
     * 纬度，范围 -90.0 ~ 90.0，精度 7 位小数。
     */
    private BigDecimal latitude;

    /** 门店联系电话，可以是座机（010-12345678）或手机号。 */
    private String phone;

    /** 营业时间描述，纯文本，如「周一至周日 10:00-22:00」。 */
    private String businessHours;

    /**
     * 综合评分，0.0~5.0，精度一位小数。
     * BigDecimal 对应 DECIMAL(2,1)，避免浮点数计算误差影响排序。
     */
    private BigDecimal score;

    /**
     * 门店价格（分），如 9900 = 99 元。
     * 用途：下单时作为 order_info.original_amount 的来源。
     * 单位「分」而非元：避免浮点数精度问题（BigDecimal 主要用于地理坐标存储）。
     * 0 表示价格待定（DRAFT 状态时可以先不填）。
     */
    private Integer price;

    /**
     * 门店状态：DRAFT / ONLINE / OFFLINE / CLOSED。
     * 用户端只能看到 ONLINE 状态的门店。
     */
    private String status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
