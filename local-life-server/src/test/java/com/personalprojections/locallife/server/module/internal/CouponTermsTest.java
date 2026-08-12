package com.personalprojections.locallife.server.module.internal;

import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Update;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CouponTermsTest {

    @Test
    void canonicalJsonAndDigestMatchCrossServiceVector() {
        CouponTerms terms = new CouponTerms(
                1,
                "1001",
                "2001",
                "3001",
                "CASH",
                2000,
                0,
                30
        );

        assertThat(terms.canonicalJson()).isEqualTo(
                "{\"terms_version\":1,\"coupon_template_id\":\"1001\","
                        + "\"shop_id\":\"2001\",\"merchant_id\":\"3001\","
                        + "\"discount_type\":\"CASH\",\"discount_value\":2000,"
                        + "\"min_order_amount\":0,\"valid_days\":30}"
        );
        assertThat(terms.digest()).isEqualTo(
                "049b5d9612aadb285038e35642b0ab499e8a98dd8800906500415edf6d97f1c7"
        );
    }

    @Test
    void stockMapperUsesSingleConditionalDecrement() throws Exception {
        Update update = CouponTemplateMapper.class
                .getMethod("decrementActiveStock", long.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", Arrays.asList(update.value()))
                .replaceAll("\\s+", " ");

        assertThat(sql).contains("remain_stock = remain_stock - 1");
        assertThat(sql).contains("status = 'ACTIVE'");
        assertThat(sql).contains("remain_stock > 0");
    }
}
