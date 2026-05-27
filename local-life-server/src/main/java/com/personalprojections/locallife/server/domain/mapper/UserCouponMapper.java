package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户已领优惠券 Mapper。
 *
 * <h2>当前阶段所用方法</h2>
 * <ul>
 *   <li>{@code insert} — MQ 消费者异步写入领券记录</li>
 *   <li>{@code selectList(Wrapper)} — 查「我的券包」（按 user_id）</li>
 *   <li>{@code selectOne(Wrapper)} — 查某张券是否已被某用户领过（兜底判重）</li>
 *   <li>{@code updateById} — 核销券（couponStatus: UNUSED → USED）</li>
 * </ul>
 *
 * <h2>注意</h2>
 * <p>数据库唯一索引 uk_user_coupon_template (user_id, coupon_template_id) 会在重复 INSERT 时
 * 抛出 {@code DuplicateKeyException}，MQ 消费者需要捕获此异常做幂等处理（直接忽略，不重试）。
 */
@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {
}
