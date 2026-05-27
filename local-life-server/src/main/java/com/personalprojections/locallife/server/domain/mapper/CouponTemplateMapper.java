package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.CouponTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 优惠券模板 Mapper。
 *
 * <h2>当前阶段所用方法（BaseMapper 提供）</h2>
 * <ul>
 *   <li>{@code selectById} — 查询单个模板（秒杀时获取券规则）</li>
 *   <li>{@code selectList(Wrapper)} — 查询可抢的券列表（status = ACTIVE）</li>
 *   <li>{@code updateById} — 同步 remainStock 快照</li>
 * </ul>
 */
@Mapper
public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {
}
