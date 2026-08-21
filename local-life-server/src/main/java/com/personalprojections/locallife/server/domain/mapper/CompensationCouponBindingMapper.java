package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.CompensationCouponBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CompensationCouponBindingMapper extends BaseMapper<CompensationCouponBinding> {

    @Select("""
            SELECT id, shop_id, merchant_id, face_value_minor,
                   coupon_template_id, enabled, created_at, updated_at
            FROM compensation_coupon_binding
            WHERE shop_id = #{shopId}
            ORDER BY face_value_minor
            """)
    List<CompensationCouponBinding> selectByShopId(@Param("shopId") long shopId);

    @Select("""
            SELECT id, shop_id, merchant_id, face_value_minor,
                   coupon_template_id, enabled, created_at, updated_at
            FROM compensation_coupon_binding
            WHERE shop_id = #{shopId}
              AND face_value_minor = #{faceValueMinor}
            """)
    CompensationCouponBinding selectByShopAndFace(
            @Param("shopId") long shopId,
            @Param("faceValueMinor") int faceValueMinor
    );

    @Select("""
            SELECT id, shop_id, merchant_id, face_value_minor,
                   coupon_template_id, enabled, created_at, updated_at
            FROM compensation_coupon_binding
            WHERE shop_id = #{shopId}
              AND coupon_template_id = #{couponTemplateId}
            """)
    CompensationCouponBinding selectByShopAndTemplate(
            @Param("shopId") long shopId,
            @Param("couponTemplateId") long couponTemplateId
    );

    @Select("""
            SELECT id, shop_id, merchant_id, face_value_minor,
                   coupon_template_id, enabled, created_at, updated_at
            FROM compensation_coupon_binding
            WHERE shop_id = #{shopId}
              AND face_value_minor = #{faceValueMinor}
              AND enabled = 1
            """)
    CompensationCouponBinding selectEnabled(
            @Param("shopId") long shopId,
            @Param("faceValueMinor") int faceValueMinor
    );
}
