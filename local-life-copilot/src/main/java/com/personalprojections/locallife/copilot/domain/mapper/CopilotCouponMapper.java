package com.personalprojections.locallife.copilot.domain.mapper;

import com.personalprojections.locallife.copilot.domain.dto.CouponTemplateSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Copilot 专用只读 Coupon Mapper。
 *
 * <p>查询 coupon_template 表获取券策略，供 CouponPolicyLookupTool 使用。
 * 与 CopilotOrderMapper 同理：Copilot 只做 SELECT，不修改业务数据。
 */
@Mapper
public interface CopilotCouponMapper {

    /**
     * 按 ID 查询券模板详情（含商家关联信息）。
     *
     * <p>JOIN shop 表是为了拿到 merchant_id，
     * 用于 RBAC 校验（merchant 角色只能看自己门店的券）。
     *
     * @param id 券模板 ID
     * @return 券模板只读快照，不存在时返回 null
     */
    @Select("""
            SELECT
                ct.id            AS coupon_template_id,
                ct.coupon_name,
                ct.discount_type,
                ct.discount_value,
                ct.min_order_amount,
                ct.total_stock,
                ct.remaining_stock,
                ct.status,
                ct.start_time,
                ct.end_time,
                ct.shop_id,
                s.shop_name,
                s.merchant_id
            FROM coupon_template ct
            LEFT JOIN shop s ON s.id = ct.shop_id
            WHERE ct.id = #{id}
              AND ct.deleted = 0
            """)
    CouponTemplateSnapshot selectCouponTemplateById(@Param("id") Long id);

    /**
     * 查询商家所有券模板（可按状态过滤）。
     *
     * @param merchantId 商家 ID（null 时查所有商家，仅 admin/cs 使用）
     * @param status     状态过滤（null 时查全部）
     * @return 券模板列表
     */
    @Select("""
            SELECT
                ct.id            AS coupon_template_id,
                ct.coupon_name,
                ct.discount_type,
                ct.discount_value,
                ct.min_order_amount,
                ct.total_stock,
                ct.remaining_stock,
                ct.status,
                ct.start_time,
                ct.end_time,
                ct.shop_id,
                s.shop_name,
                s.merchant_id
            FROM coupon_template ct
            LEFT JOIN shop s ON s.id = ct.shop_id
            WHERE ct.deleted = 0
              AND (#{merchantId} IS NULL OR s.merchant_id = #{merchantId})
              AND (#{status}     IS NULL OR ct.status      = #{status})
            ORDER BY ct.created_at DESC
            LIMIT 50
            """)
    List<CouponTemplateSnapshot> selectCouponTemplatesByMerchant(
            @Param("merchantId") Long merchantId,
            @Param("status") String status);
}
