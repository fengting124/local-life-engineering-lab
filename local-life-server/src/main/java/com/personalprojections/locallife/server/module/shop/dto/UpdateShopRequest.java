package com.personalprojections.locallife.server.module.shop.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新门店信息请求体（全量更新，PUT 语义）。
 *
 * <h2>接口约定</h2>
 * <pre>
 *   PUT /api/v1/shops/{shopId}
 *   Authorization: Bearer {token}
 * </pre>
 *
 * <h2>全量更新 vs 局部更新</h2>
 * <p>本接口使用 PUT（全量更新）语义，即所有字段都要传，未传字段会被置为空/默认值。
 * 优点：语义清晰，前端逻辑简单（直接提交表单所有字段）。
 * 缺点：每次都要传所有字段，即使只改了一个字段。
 * 如果后续有「只改某一个字段」的需求（如单独更新营业状态），用 PATCH 接口单独做。
 *
 * <h2>不能通过此接口修改的字段</h2>
 * <ul>
 *   <li>merchantId：不能转让门店归属</li>
 *   <li>status：状态变更通过专门的上线/下线接口操作（POST /shops/{id}/online 等）</li>
 *   <li>score：评分由后台任务计算，不能手动设置</li>
 * </ul>
 */
@Data
public class UpdateShopRequest {

    @NotBlank(message = "门店名称不能为空")
    @Size(min = 2, max = 64, message = "门店名称长度应在 2~64 字符之间")
    private String shopName;

    @NotNull(message = "门店分类不能为空")
    @Min(value = 1, message = "分类 ID 不合法")
    private Integer categoryId;

    @Size(max = 512, message = "门店简介最多 512 字符")
    private String description;

    @NotBlank(message = "门店地址不能为空")
    @Size(max = 256, message = "地址最多 256 字符")
    private String address;

    @NotNull(message = "经度不能为空")
    @DecimalMin(value = "-180.0", message = "经度范围 -180.0 ~ 180.0")
    @DecimalMax(value = "180.0", message = "经度范围 -180.0 ~ 180.0")
    private BigDecimal longitude;

    @NotNull(message = "纬度不能为空")
    @DecimalMin(value = "-90.0", message = "纬度范围 -90.0 ~ 90.0")
    @DecimalMax(value = "90.0", message = "纬度范围 -90.0 ~ 90.0")
    private BigDecimal latitude;

    @Size(max = 20, message = "联系电话最多 20 字符")
    private String phone;

    @Size(max = 128, message = "营业时间描述最多 128 字符")
    private String businessHours;
}
