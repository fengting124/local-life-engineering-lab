package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.SeckillSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * 秒杀场次 Mapper。
 *
 * <h2>当前阶段所用方法</h2>
 * <ul>
 *   <li>{@code selectById} — 查询场次详情（校验时间窗、状态）</li>
 *   <li>{@code selectList(Wrapper)} — 查询 ACTIVE 场次列表（定时任务、C 端展示）</li>
 *   <li>{@code updateById} — 更新场次状态（PENDING→ACTIVE，ACTIVE→ENDED）</li>
 * </ul>
 */
@Mapper
public interface SeckillSessionMapper extends BaseMapper<SeckillSession> {
}
