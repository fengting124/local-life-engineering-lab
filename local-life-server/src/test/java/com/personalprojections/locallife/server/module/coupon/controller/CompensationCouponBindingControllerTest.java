package com.personalprojections.locallife.server.module.coupon.controller;

import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.interceptor.AuthInterceptor;
import com.personalprojections.locallife.server.common.ratelimit.RateLimitInterceptor;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.module.coupon.dto.CompensationCouponBindingVO;
import com.personalprojections.locallife.server.module.coupon.service.CompensationCouponBindingService;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CompensationCouponBindingController.class)
class CompensationCouponBindingControllerTest {

    private static final long SHOP_ID = 2001L;
    private static final int FACE_VALUE = 2000;
    private static final long TEMPLATE_ID = 4001L;

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private CompensationCouponBindingService service;
    @MockitoBean
    private Tracer tracer;
    @MockitoBean
    private AuthInterceptor authInterceptor;
    @MockitoBean
    private RateLimitInterceptor rateLimitInterceptor;
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private SqlSessionTemplate sqlSessionTemplate;

    @BeforeEach
    void allowRequestThroughInfrastructure() throws Exception {
        when(authInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void listReturnsOwnedShopBindings() throws Exception {
        when(service.list(SHOP_ID)).thenReturn(List.of(binding(true, "READY")));

        mockMvc.perform(get("/api/v1/shops/{shopId}/compensation-coupon-bindings", SHOP_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].shopId").value("2001"))
                .andExpect(jsonPath("$.data[0].merchantId").value("3001"))
                .andExpect(jsonPath("$.data[0].couponTemplateId").value("4001"))
                .andExpect(jsonPath("$.data[0].faceValueMinor").value(2000))
                .andExpect(jsonPath("$.data[0].configurationStatus").value("READY"));

        verify(service).list(SHOP_ID);
    }

    @Test
    void getReturnsOneBinding() throws Exception {
        when(service.get(SHOP_ID, FACE_VALUE)).thenReturn(binding(false, "DISABLED"));

        mockMvc.perform(get(
                        "/api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValue}",
                        SHOP_ID, FACE_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.configurationStatus").value("DISABLED"));

        verify(service).get(SHOP_ID, FACE_VALUE);
    }

    @Test
    void putBindsOnlyTemplateIdAndReturnsCurrentRepresentation() throws Exception {
        when(service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID))
                .thenReturn(binding(true, "READY"));

        mockMvc.perform(put(
                        "/api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValue}",
                        SHOP_ID, FACE_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"couponTemplateId":"4001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.couponTemplateId").value("4001"))
                .andExpect(jsonPath("$.data.enabled").value(true));

        verify(service).upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID);
    }

    @Test
    void putRejectsMissingOrNonPositiveTemplateIdBeforeService() throws Exception {
        mockMvc.perform(put(
                        "/api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValue}",
                        SHOP_ID, FACE_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"));

        mockMvc.perform(put(
                        "/api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValue}",
                        SHOP_ID, FACE_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"couponTemplateId":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"));

        verify(service, org.mockito.Mockito.never())
                .upsert(any(Long.class), any(Integer.class), any(Long.class));
    }

    @Test
    void disableReturnsDisabledRepresentation() throws Exception {
        when(service.disable(SHOP_ID, FACE_VALUE)).thenReturn(binding(false, "DISABLED"));

        mockMvc.perform(put(
                        "/api/v1/shops/{shopId}/compensation-coupon-bindings/{faceValue}/status/disabled",
                        SHOP_ID, FACE_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        verify(service).disable(SHOP_ID, FACE_VALUE);
    }

    @Test
    void serviceOwnershipFailurePreservesBusinessHttpCode() throws Exception {
        when(service.list(SHOP_ID)).thenThrow(new BizException(ErrorCode.SHOP_FORBIDDEN));

        mockMvc.perform(get("/api/v1/shops/{shopId}/compensation-coupon-bindings", SHOP_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHOP_FORBIDDEN"));
    }

    private static CompensationCouponBindingVO binding(boolean enabled, String status) {
        return CompensationCouponBindingVO.builder()
                .bindingId("6001")
                .shopId("2001")
                .merchantId("3001")
                .faceValueMinor(FACE_VALUE)
                .couponTemplateId("4001")
                .discountType("CASH")
                .discountValue(FACE_VALUE)
                .minOrderAmount(0)
                .validDays(30)
                .templateStatus("ACTIVE")
                .remainStock(10)
                .enabled(enabled)
                .configurationStatus(status)
                .build();
    }
}
