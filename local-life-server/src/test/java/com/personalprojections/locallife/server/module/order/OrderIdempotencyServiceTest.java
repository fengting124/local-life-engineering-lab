package com.personalprojections.locallife.server.module.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.OrderIdempotency;
import com.personalprojections.locallife.server.module.order.dto.CreateOrderRequest;
import com.personalprojections.locallife.server.module.order.dto.OrderVO;
import com.personalprojections.locallife.server.module.order.service.OrderIdempotencyClaimService;
import com.personalprojections.locallife.server.module.order.service.OrderIdempotencyExecutionService;
import com.personalprojections.locallife.server.module.order.service.OrderIdempotencyService;
import com.personalprojections.locallife.server.module.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderIdempotencyServiceTest {

    private static final long USER_ID = 7777L;
    private static final String KEY = "order-request-001";

    @Mock private OrderService orderService;
    @Mock private OrderIdempotencyClaimService claimService;
    @Mock private OrderIdempotencyExecutionService executionService;

    private ObjectMapper objectMapper;
    private OrderIdempotencyService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new OrderIdempotencyService(
                orderService,
                claimService,
                executionService,
                objectMapper);
        UserContext.set(LoginUserDTO.builder().userId(USER_ID).status("ENABLED").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void blankKeyDelegatesWithoutCreatingLedger() {
        CreateOrderRequest request = request(1L, null);
        OrderVO expected = order("1001");
        when(orderService.createOrder(request, null)).thenReturn(expected);

        OrderVO actual = service.createOrder(request, "  ");

        assertThat(actual).isSameAs(expected);
        verify(claimService, never()).tryClaim(USER_ID, KEY, hash(request));
    }

    @Test
    void firstRequestClaimsAndExecutesBusinessTransaction() {
        CreateOrderRequest request = request(1L, null);
        String requestHash = hash(request);
        OrderVO expected = order("1002");
        when(claimService.tryClaim(USER_ID, KEY, requestHash)).thenReturn(true);
        when(executionService.execute(request, USER_ID, KEY, requestHash)).thenReturn(expected);

        OrderVO actual = service.createOrder(request, KEY);

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void completedDuplicateReplaysStoredResponse() throws Exception {
        CreateOrderRequest request = request(1L, 8L);
        String requestHash = hash(request);
        OrderVO expected = order("1003");
        when(claimService.tryClaim(USER_ID, KEY, requestHash)).thenReturn(false);
        when(claimService.get(USER_ID, KEY)).thenReturn(
                OrderIdempotency.builder()
                        .userId(USER_ID)
                        .idempotencyKey(KEY)
                        .requestHash(requestHash)
                        .status("SUCCESS")
                        .responseJson(objectMapper.writeValueAsString(expected))
                        .build());

        OrderVO actual = service.createOrder(request, KEY);

        assertThat(actual.getOrderNo()).isEqualTo("1003");
        verify(executionService, never()).execute(request, USER_ID, KEY, requestHash);
    }

    @Test
    void sameKeyWithDifferentRequestBodyIsRejected() {
        CreateOrderRequest request = request(1L, null);
        String requestHash = hash(request);
        when(claimService.tryClaim(USER_ID, KEY, requestHash)).thenReturn(false);
        when(claimService.get(USER_ID, KEY)).thenReturn(
                OrderIdempotency.builder()
                        .userId(USER_ID)
                        .idempotencyKey(KEY)
                        .requestHash("different-hash")
                        .status("SUCCESS")
                        .responseJson("{}")
                        .build());

        assertThatThrownBy(() -> service.createOrder(request, KEY))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode())
                        .isEqualTo(ErrorCode.SYS_PARAM_INVALID));
    }

    @Test
    void failedRequestCanBeReclaimed() {
        CreateOrderRequest request = request(1L, null);
        String requestHash = hash(request);
        OrderVO expected = order("1004");
        when(claimService.tryClaim(USER_ID, KEY, requestHash)).thenReturn(false);
        when(claimService.get(USER_ID, KEY)).thenReturn(
                OrderIdempotency.builder()
                        .userId(USER_ID)
                        .idempotencyKey(KEY)
                        .requestHash(requestHash)
                        .status("FAILED")
                        .build());
        when(claimService.reclaimFailedOrExpired(USER_ID, KEY, requestHash)).thenReturn(true);
        when(executionService.execute(request, USER_ID, KEY, requestHash)).thenReturn(expected);

        OrderVO actual = service.createOrder(request, KEY);

        assertThat(actual.getOrderNo()).isEqualTo("1004");
    }

    @Test
    void keyLongerThanDatabaseLimitIsRejected() {
        CreateOrderRequest request = request(1L, null);

        assertThatThrownBy(() -> service.createOrder(request, "x".repeat(65)))
                .isInstanceOf(BizException.class)
                .satisfies(error -> assertThat(((BizException) error).getErrorCode())
                        .isEqualTo(ErrorCode.SYS_PARAM_INVALID));
    }

    private CreateOrderRequest request(Long shopId, Long couponId) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        request.setUserCouponId(couponId);
        request.setRemark("test");
        return request;
    }

    private OrderVO order(String orderNo) {
        return OrderVO.builder()
                .orderId(orderNo)
                .orderNo(orderNo)
                .shopId("1")
                .orderStatus("WAIT_PAY")
                .originalAmount(1000)
                .couponDiscount(0)
                .orderAmount(1000)
                .build();
    }

    private String hash(CreateOrderRequest request) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
