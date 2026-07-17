package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 创建订单请求的数据库幂等账本。
 *
 * <p>唯一约束为 {@code (user_id, idempotency_key)}。相同 Key 只能绑定同一份请求体，
 * 首次成功响应序列化保存，后续重试直接回放。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("order_idempotency")
public class OrderIdempotency {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String idempotencyKey;

    private String requestHash;

    private String status;

    private String responseJson;

    private String failureReason;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
