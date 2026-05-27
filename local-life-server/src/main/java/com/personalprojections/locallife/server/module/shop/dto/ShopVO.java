package com.personalprojections.locallife.server.module.shop.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 门店信息 VO，返回给前端展示。
 *
 * <h2>展示场景</h2>
 * <ul>
 *   <li>门店详情页：全字段展示</li>
 *   <li>搜索结果列表：可能只展示部分字段（shopName, coverImage, score, address），
 *       复杂场景后续可以新建 ShopSummaryVO 精简版，当前用同一个 VO 即可</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopVO {

    /** 门店 ID，字符串（雪花 ID 防 JS 精度截断）。 */
    private String shopId;

    /** 所属商家 ID，字符串。 */
    private String merchantId;

    /** 门店名称。 */
    private String shopName;

    /** 分类 ID。 */
    private Integer categoryId;

    /** 封面图 URL。 */
    private String coverImage;

    /** 门店简介。 */
    private String description;

    /** 详细地址。 */
    private String address;

    /**
     * 经度，BigDecimal 序列化后是 JSON 数字，如 120.1234567。
     * 前端地图 SDK（高德/百度）直接使用此值定位。
     */
    private BigDecimal longitude;

    /** 纬度。 */
    private BigDecimal latitude;

    /** 联系电话。 */
    private String phone;

    /** 营业时间描述。 */
    private String businessHours;

    /**
     * 综合评分，0.0~5.0，一位小数。
     * 前端展示星级评分时使用此字段。
     */
    private BigDecimal score;

    /**
     * 门店状态：DRAFT / ONLINE / OFFLINE / CLOSED。
     * 商家管理后台需要展示状态，C 端只展示 ONLINE 的门店（查询时已过滤）。
     */
    private String status;
}
