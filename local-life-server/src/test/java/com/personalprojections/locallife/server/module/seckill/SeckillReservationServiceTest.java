package com.personalprojections.locallife.server.module.seckill;

import com.personalprojections.locallife.server.domain.entity.SeckillReservation;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import com.personalprojections.locallife.server.domain.mapper.SeckillReservationMapper;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import com.personalprojections.locallife.server.module.seckill.service.SeckillReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillReservationServiceTest {

    @Mock private SeckillReservationMapper reservationMapper;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SetOperations<String, String> setOperations;

    @Test
    void confirmMarksReservationConfirmedWhenCouponExists() {
        SeckillReservationService service = newService();
        when(userCouponMapper.selectOne(any())).thenReturn(UserCoupon.builder().id(1L).build());

        boolean confirmed = service.confirmIfCouponExists(reservation());

        assertThat(confirmed).isTrue();
        verify(reservationMapper).markConfirmed("100_200_300_seckill");
    }

    @Test
    void compensateReturnsRedisStockAndRemovesUserMarker() {
        SeckillReservationService service = newService();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        service.compensate(reservation());

        verify(valueOperations).increment("seckill:stock:100:200");
        verify(setOperations).remove("seckill:user:100:200", "300");
        verify(valueOperations).set(
                eq("seckill:result:100:200:300"),
                eq("COMPENSATED"),
                anyLong(),
                any());
        verify(reservationMapper).markCompensated("100_200_300_seckill");
    }

    @Test
    void reconcileExpiredPendingConfirmsExistingCouponAndCompensatesMissingCoupon() {
        SeckillReservation existing = reservation();
        SeckillReservation missing = SeckillReservation.builder()
                .reservationId("101_201_301_seckill")
                .sessionId(101L)
                .couponTemplateId(201L)
                .userId(301L)
                .status("PENDING")
                .build();
        SeckillReservationService service = newService();
        when(reservationMapper.selectExpiredPending(any(), eq(50))).thenReturn(List.of(existing, missing));
        when(userCouponMapper.selectOne(any()))
                .thenReturn(UserCoupon.builder().id(1L).build())
                .thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        int handled = service.reconcileExpiredPending(LocalDateTime.now(), 50);

        assertThat(handled).isEqualTo(2);
        verify(reservationMapper).markConfirmed("100_200_300_seckill");
        verify(reservationMapper).markCompensated("101_201_301_seckill");
    }

    private SeckillReservationService newService() {
        return new SeckillReservationService(reservationMapper, userCouponMapper, redisTemplate);
    }

    private SeckillReservation reservation() {
        return SeckillReservation.builder()
                .reservationId("100_200_300_seckill")
                .sessionId(100L)
                .couponTemplateId(200L)
                .userId(300L)
                .status("PENDING")
                .build();
    }
}
