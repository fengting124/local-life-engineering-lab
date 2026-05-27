package com.personalprojections.locallife.server.module.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.personalprojections.locallife.server.domain.entity.CouponTemplate;
import com.personalprojections.locallife.server.domain.entity.SeckillSession;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import com.personalprojections.locallife.server.domain.mapper.SeckillSessionMapper;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.module.seckill.dto.CouponTemplateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 优惠券展示 Service，负责 C 端查询「可抢的券列表」。
 *
 * <h2>与 SeckillService 的职责分工</h2>
 * <ul>
 *   <li>CouponService：只负责「展示」，查询可抢的券模板 + 关联场次信息</li>
 *   <li>SeckillService：只负责「抢」，执行秒杀主流程（Lua 脚本 + 写 DB）</li>
 * </ul>
 * <p>分开是因为「查」和「抢」的读写比例、并发压力完全不同：
 * 查是高读低写（可以加缓存），抢是高并发写（需要 Lua 原子操作）。
 *
 * <h2>查询逻辑</h2>
 * <p>「可抢的券」= ACTIVE 状态的 coupon_template + 有 ACTIVE 场次的 seckill_session。
 * 当前时间在 [beginTime, endTime] 内的场次才展示，已过期的场次不展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponTemplateMapper couponTemplateMapper;
    private final SeckillSessionMapper seckillSessionMapper;
    private final ShopMapper shopMapper;

    /**
     * 查询当前正在进行秒杀的券模板列表（C 端，公开，无需登录）。
     *
     * <p>「正在进行」的定义：
     * <ul>
     *   <li>coupon_template.status = ACTIVE</li>
     *   <li>存在 seckill_session，其 session_status = ACTIVE</li>
     *   <li>当前时间在 session.begin_time 到 session.end_time 之间</li>
     * </ul>
     *
     * <p>查询策略（两步查，避免 JOIN 复杂度）：
     * <ol>
     *   <li>查所有时间窗内的 ACTIVE 场次</li>
     *   <li>根据场次的 couponTemplateId 批量查券模板</li>
     *   <li>批量查门店信息（冗余门店名到 VO）</li>
     *   <li>拼装 VO</li>
     * </ol>
     *
     * @return 当前可抢的券模板 VO 列表（含场次信息）
     */
    public List<CouponTemplateVO> listActiveCoupons() {
        LocalDateTime now = LocalDateTime.now();

        // 1. 查当前时间窗内的 ACTIVE 场次（beginTime <= now <= endTime）
        List<SeckillSession> activeSessions = seckillSessionMapper.selectList(
                new LambdaQueryWrapper<SeckillSession>()
                        .eq(SeckillSession::getSessionStatus, "ACTIVE")
                        .le(SeckillSession::getBeginTime, now)   // begin_time ≤ now
                        .ge(SeckillSession::getEndTime, now)     // end_time ≥ now
        );

        if (activeSessions.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 批量查关联的券模板
        List<Long> templateIds = activeSessions.stream()
                .map(SeckillSession::getCouponTemplateId)
                .distinct()
                .collect(Collectors.toList());

        List<CouponTemplate> templates = couponTemplateMapper.selectList(
                new LambdaQueryWrapper<CouponTemplate>()
                        .in(CouponTemplate::getId, templateIds)
                        .eq(CouponTemplate::getStatus, "ACTIVE")
        );

        if (templates.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. 批量查门店名（冗余到 VO，避免前端额外查询）
        List<Long> shopIds = templates.stream()
                .map(CouponTemplate::getShopId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> shopNameMap = shopMapper.selectBatchIds(shopIds)
                .stream()
                .collect(Collectors.toMap(Shop::getId, Shop::getShopName));

        // 4. 以模板 ID 为 key 建 Map，方便拼装 VO 时查找
        Map<Long, CouponTemplate> templateMap = templates.stream()
                .collect(Collectors.toMap(CouponTemplate::getId, t -> t));

        // 5. 按场次组装 VO（一个模板可能有多个场次，但当前时间下通常只有一个 ACTIVE 场次）
        ZoneOffset east8 = ZoneOffset.ofHours(8);
        return activeSessions.stream()
                .filter(s -> templateMap.containsKey(s.getCouponTemplateId()))
                .map(session -> {
                    CouponTemplate template = templateMap.get(session.getCouponTemplateId());
                    String shopName = shopNameMap.getOrDefault(template.getShopId(), "");
                    return CouponTemplateVO.builder()
                            .templateId(String.valueOf(template.getId()))
                            .shopId(String.valueOf(template.getShopId()))
                            .shopName(shopName)
                            .couponName(template.getCouponName())
                            .discountType(template.getDiscountType())
                            .discountValue(template.getDiscountValue())
                            .minOrderAmount(template.getMinOrderAmount())
                            .remainStock(template.getRemainStock())
                            .perUserLimit(template.getPerUserLimit())
                            .validDays(template.getValidDays())
                            .sessionId(String.valueOf(session.getId()))
                            .beginTime(session.getBeginTime().atOffset(east8))
                            .endTime(session.getEndTime().atOffset(east8))
                            .build();
                })
                .collect(Collectors.toList());
    }
}
