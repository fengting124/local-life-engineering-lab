package com.personalprojections.locallife.server.module.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.personalprojections.locallife.server.domain.entity.SeckillReservation;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import com.personalprojections.locallife.server.domain.mapper.SeckillReservationMapper;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeckillReservationService {

    private static final String STOCK_KEY = "seckill:stock:%d:%d";
    private static final String USER_SET_KEY = "seckill:user:%d:%d";
    private static final String RESULT_KEY = "seckill:result:%d:%d:%d";
    private static final String RESULT_COMPENSATED = "COMPENSATED";
    private static final long RESULT_KEY_TTL_SECONDS = 86_400L;
    private static final int RECONCILE_BATCH_SIZE = 100;
    private static final int PENDING_GRACE_MINUTES = 5;

    private final SeckillReservationMapper reservationMapper;
    private final UserCouponMapper userCouponMapper;
    private final StringRedisTemplate redisTemplate;

    public boolean confirmIfCouponExists(SeckillReservation reservation) {
        UserCoupon existing = userCouponMapper.selectOne(
                new LambdaQueryWrapper<UserCoupon>()
                        .eq(UserCoupon::getUserId, reservation.getUserId())
                        .eq(UserCoupon::getCouponTemplateId, reservation.getCouponTemplateId())
                        .eq(UserCoupon::getSeckillSessionId, reservation.getSessionId())
                        .last("LIMIT 1")
        );
        if (existing == null) {
            return false;
        }
        reservationMapper.markConfirmed(reservation.getReservationId());
        return true;
    }

    public void compensate(SeckillReservation reservation) {
        String stockKey = String.format(STOCK_KEY, reservation.getSessionId(), reservation.getCouponTemplateId());
        String userSetKey = String.format(USER_SET_KEY, reservation.getSessionId(), reservation.getCouponTemplateId());
        String resultKey = String.format(
                RESULT_KEY,
                reservation.getSessionId(),
                reservation.getCouponTemplateId(),
                reservation.getUserId());

        redisTemplate.opsForValue().increment(stockKey);
        redisTemplate.opsForSet().remove(userSetKey, String.valueOf(reservation.getUserId()));
        redisTemplate.opsForValue().set(
                resultKey,
                RESULT_COMPENSATED,
                RESULT_KEY_TTL_SECONDS,
                TimeUnit.SECONDS);
        reservationMapper.markCompensated(reservation.getReservationId());
    }

    public int reconcileExpiredPending(LocalDateTime before, int limit) {
        List<SeckillReservation> reservations = reservationMapper.selectExpiredPending(before, limit);
        int handled = 0;
        for (SeckillReservation reservation : reservations) {
            if (!confirmIfCouponExists(reservation)) {
                compensate(reservation);
            }
            handled++;
        }
        return handled;
    }

    @Scheduled(fixedDelay = 60_000)
    public void reconcileExpiredPendingReservations() {
        LocalDateTime before = LocalDateTime.now().minusMinutes(PENDING_GRACE_MINUTES);
        int handled = reconcileExpiredPending(before, RECONCILE_BATCH_SIZE);
        if (handled > 0) {
            log.warn("[SeckillReservation] reconciled {} expired PENDING reservations", handled);
        }
    }
}
