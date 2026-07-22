package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.SeckillReservation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SeckillReservationMapper extends BaseMapper<SeckillReservation> {

    @Insert("""
            INSERT INTO seckill_reservation
                (reservation_id, session_id, coupon_template_id, user_id, status, reserved_at)
            VALUES
                (#{reservationId}, #{sessionId}, #{couponTemplateId}, #{userId}, #{status}, #{reservedAt})
            ON DUPLICATE KEY UPDATE reservation_id = reservation_id
            """)
    int insertIfAbsent(SeckillReservation reservation);

    @Update("""
            UPDATE seckill_reservation
            SET status = 'CONFIRMED', updated_at = NOW()
            WHERE reservation_id = #{reservationId}
              AND status IN ('PENDING', 'CONFIRMED')
            """)
    int markConfirmed(@Param("reservationId") String reservationId);

    @Update("""
            UPDATE seckill_reservation
            SET status = 'COMPENSATED', updated_at = NOW()
            WHERE reservation_id = #{reservationId}
              AND status IN ('PENDING', 'COMPENSATED')
            """)
    int markCompensated(@Param("reservationId") String reservationId);

    @Select("""
            SELECT reservation_id, session_id, coupon_template_id, user_id, status, reserved_at,
                   created_at, updated_at
            FROM seckill_reservation
            WHERE status = 'PENDING'
              AND reserved_at <= #{before}
            ORDER BY reserved_at ASC
            LIMIT #{limit}
            """)
    List<SeckillReservation> selectExpiredPending(
            @Param("before") LocalDateTime before,
            @Param("limit") int limit);
}
