package com.personalprojections.locallife.server.module.merchant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商家信息 VO，返回给前端展示。
 *
 * <h2>不返回的字段</h2>
 * <ul>
 *   <li>userId：内部关联字段，不暴露</li>
 *   <li>deleted：逻辑删除标记，不暴露</li>
 *   <li>contactMobile：敏感信息，仅商家自己可见（查自己时返回，查他人时 null）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantVO {

    /** 商家 ID，字符串（雪花 ID 防 JS 精度截断）。 */
    private String merchantId;

    /** 商家名称。 */
    private String merchantName;

    /** 商家 logo URL。 */
    private String logo;

    /** 商家简介。 */
    private String description;

    /**
     * 运营联系手机号（脱敏），仅查询自己时返回，查他人时为 null。
     * Jackson 配置了 non_null，null 字段不出现在响应 JSON 中。
     */
    private String contactMobile;

    /**
     * 商家状态：PENDING / APPROVED / REJECTED / DISABLED。
     * 前端根据状态展示对应提示（如「审核中」「已通过」）。
     */
    private String status;
}
