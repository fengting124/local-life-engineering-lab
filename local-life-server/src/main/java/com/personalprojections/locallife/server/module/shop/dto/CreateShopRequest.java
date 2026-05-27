package com.personalprojections.locallife.server.module.shop.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建门店请求体。
 *
 * <h2>接口约定</h2>
 * <pre>
 *   POST /api/v1/shops
 *   Authorization: Bearer {token}（需要登录，且当前用户必须是 APPROVED 状态的商家）
 *   Content-Type: application/json
 * </pre>
 *
 * <h2>鉴权说明</h2>
 * <p>不是所有登录用户都能创建门店，Service 层会额外校验：
 * <ol>
 *   <li>当前用户必须有 merchant 记录（已申请成为商家）</li>
 *   <li>merchant.status 必须是 APPROVED（审核通过）</li>
 * </ol>
 */
@Data
public class CreateShopRequest {

    /** 门店名称，2~64 字符。 */
    @NotBlank(message = "门店名称不能为空")
    @Size(min = 2, max = 64, message = "门店名称长度应在 2~64 字符之间")
    private String shopName;

    /**
     * 分类 ID，必须大于 0。
     * 第一阶段分类固定：1=餐饮 2=酒店 3=娱乐 4=购物 5=美妆 6=其他。
     */
    @NotNull(message = "门店分类不能为空")
    @Min(value = 1, message = "分类 ID 不合法")
    private Integer categoryId;

    /** 门店简介，最多 512 字符，可选。 */
    @Size(max = 512, message = "门店简介最多 512 字符")
    private String description;

    /** 详细地址，不能为空。 */
    @NotBlank(message = "门店地址不能为空")
    @Size(max = 256, message = "地址最多 256 字符")
    private String address;

    /**
     * 经度，-180.0 ~ 180.0。
     * {@code @DecimalMin / @DecimalMax}：校验 BigDecimal 的范围，inclusive=true 包含边界值。
     */
    @NotNull(message = "经度不能为空")
    @DecimalMin(value = "-180.0", message = "经度范围 -180.0 ~ 180.0")
    @DecimalMax(value = "180.0", message = "经度范围 -180.0 ~ 180.0")
    private BigDecimal longitude;

    /** 纬度，-90.0 ~ 90.0。 */
    @NotNull(message = "纬度不能为空")
    @DecimalMin(value = "-90.0", message = "纬度范围 -90.0 ~ 90.0")
    @DecimalMax(value = "90.0", message = "纬度范围 -90.0 ~ 90.0")
    private BigDecimal latitude;

    /** 联系电话，可选，座机或手机均可。 */
    @Size(max = 20, message = "联系电话最多 20 字符")
    private String phone;

    /** 营业时间描述，可选，如「周一至周日 10:00-22:00」。 */
    @Size(max = 128, message = "营业时间描述最多 128 字符")
    private String businessHours;
}
