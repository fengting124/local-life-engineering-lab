package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Redis 秒杀预扣的数据库补偿账本。
 *
 * <p>Redis Lua 成功后事件先落 Redis Stream；Java 消费事件时写入本表。若写券失败或
 * 进程中断，长期 PENDING 记录会被对账任务确认或补偿，避免 Redis 库存和 MySQL 券包永久不一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("seckill_reservation")
public class SeckillReservation {

    @TableId
    private String reservationId;

    private Long sessionId;

    private Long couponTemplateId;

    private Long userId;

    private String status;

    private LocalDateTime reservedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
