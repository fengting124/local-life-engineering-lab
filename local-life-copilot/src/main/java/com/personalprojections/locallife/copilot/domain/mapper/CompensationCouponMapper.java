package com.personalprojections.locallife.copilot.domain.mapper;

import com.personalprojections.locallife.copilot.domain.dto.CompensationCouponResolution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CompensationCouponMapper {

    @Select("""
            SELECT o.id AS order_id, o.order_no, o.user_id AS target_user_id,
                   o.shop_id, s.merchant_id, b.face_value_minor,
                   b.coupon_template_id, ct.discount_type, ct.discount_value,
                   ct.min_order_amount, ct.valid_days,
                   ct.status AS template_status, b.enabled AS binding_enabled
            FROM order_info o
            JOIN shop s ON s.id = o.shop_id AND s.deleted = 0
            JOIN compensation_coupon_binding b
              ON b.shop_id = o.shop_id
             AND b.merchant_id = s.merchant_id
             AND b.face_value_minor = #{faceValueMinor}
             AND b.enabled = 1
            JOIN coupon_template ct
              ON ct.id = b.coupon_template_id
             AND ct.shop_id = o.shop_id
             AND ct.deleted = 0
            WHERE o.order_no = #{orderNo}
              AND o.deleted = 0
            """)
    CompensationCouponResolution resolve(
            @Param("orderNo") String orderNo,
            @Param("faceValueMinor") int faceValueMinor
    );
}
