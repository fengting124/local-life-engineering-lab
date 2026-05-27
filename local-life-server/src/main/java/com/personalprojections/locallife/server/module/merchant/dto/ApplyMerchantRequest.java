package com.personalprojections.locallife.server.module.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 申请成为商家的请求体。
 *
 * <h2>接口约定</h2>
 * <pre>
 *   POST /api/v1/merchants/apply
 *   Authorization: Bearer {token}（需要登录）
 *   Content-Type: application/json
 * </pre>
 *
 * <h2>业务规则</h2>
 * <ul>
 *   <li>同一用户只能申请一次，重复申请返回 MERCHANT_ALREADY_APPLIED</li>
 *   <li>申请成功后状态为 PENDING，等待运营审核</li>
 *   <li>当前阶段无真实审核流程，提交后自动设为 APPROVED（模拟通过）</li>
 * </ul>
 */
@Data
public class ApplyMerchantRequest {

    /** 商家名称，2~64 字符。 */
    @NotBlank(message = "商家名称不能为空")
    @Size(min = 2, max = 64, message = "商家名称长度应在 2~64 字符之间")
    private String merchantName;

    /** 商家简介，最多 256 字符，可选。 */
    @Size(max = 256, message = "商家简介最多 256 字符")
    private String description;

    /** 运营联系手机号，11 位数字。 */
    @NotBlank(message = "联系手机号不能为空")
    @Pattern(regexp = "^1[0-9]{10}$", message = "联系手机号格式不正确")
    private String contactMobile;
}
