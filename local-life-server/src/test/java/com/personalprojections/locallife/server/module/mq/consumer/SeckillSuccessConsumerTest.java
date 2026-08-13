package com.personalprojections.locallife.server.module.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import com.personalprojections.locallife.server.domain.mapper.SeckillReservationMapper;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import com.personalprojections.locallife.server.module.mq.event.SeckillSuccessEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillSuccessConsumerTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private SeckillReservationMapper reservationMapper;

    @Test
    void successfulEventWritesExplicitSeckillIssuanceIdentity() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(86_400L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        SeckillSuccessEvent event = SeckillSuccessEvent.builder()
                .eventId("7001_5001_seckill")
                .sessionId(7001L)
                .couponTemplateId(6001L)
                .userId(5001L)
                .validDays(7)
                .succeededAt(LocalDateTime.now())
                .eventAt(LocalDateTime.now())
                .build();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SeckillSuccessConsumer consumer = new SeckillSuccessConsumer(
                objectMapper, redisTemplate, userCouponMapper, reservationMapper);

        consumer.onMessage(objectMapper.writeValueAsString(event));

        ArgumentCaptor<UserCoupon> coupon = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).insert(coupon.capture());
        assertThat(coupon.getValue().getSourceType()).isEqualTo("SECKILL");
        assertThat(coupon.getValue().getSourceApprovalId()).isNull();
        assertThat(coupon.getValue().getIssuanceKey()).isEqualTo("SECKILL:5001:6001");
        verify(reservationMapper).markConfirmed("7001_5001_seckill");
        verify(valueOperations).set(anyString(), eq("SUCCESS"), eq(86_400L), eq(TimeUnit.SECONDS));
    }
}
