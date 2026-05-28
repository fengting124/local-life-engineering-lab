package com.personalprojections.locallife.server.module.seckill.controller;

import com.personalprojections.locallife.server.common.ratelimit.RateLimit;
import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import com.personalprojections.locallife.server.module.seckill.dto.CouponTemplateVO;
import com.personalprojections.locallife.server.module.seckill.dto.SeckillRequest;
import com.personalprojections.locallife.server.module.seckill.dto.SeckillResultVO;
import com.personalprojections.locallife.server.module.seckill.service.CouponService;
import com.personalprojections.locallife.server.module.seckill.service.SeckillService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 优惠券 & 秒杀 Controller。
 *
 * <h2>接口列表</h2>
 * <pre>
 *   GET  /api/v1/coupons/templates          查询当前可抢的券列表（公开，白名单）
 *   POST /api/v1/seckill                    参与秒杀抢券（需登录）
 *   GET  /api/v1/seckill/result             查询抢券结果（需登录，轮询用）
 *   GET  /api/v1/coupons                    我的券包（需登录）
 * </pre>
 *
 * <h2>秒杀接口的并发承压能力</h2>
 * <p>秒杀主链路（POST /seckill）的核心操作都在 Redis（Lua 脚本），
 * 数据库写入是后置的（当前同步、未来 MQ 异步），所以 QPS 瓶颈在 Redis 而非 DB。
 * Redis 单机读写 QPS 可达 10 万+，足以应对中等规模秒杀场景。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SeckillController {

    private final CouponService couponService;
    private final SeckillService seckillService;

    // =========================================================
    // 券展示（公开，白名单）
    // =========================================================

    /**
     * 查询当前正在进行秒杀的优惠券列表（公开，无需登录）。
     *
     * <p>返回内容：所有 ACTIVE 状态的秒杀场次 + 对应券模板信息。
     * 前端展示倒计时（用 beginTime 和 endTime 计算），引导用户点「立即抢购」。
     *
     * <p>此接口已加入 WebMvcConfig 白名单（/api/v1/coupons/templates），无需 Token。
     *
     * @return 可抢的券模板 VO 列表
     */
    @GetMapping("/api/v1/coupons/templates")
    public Result<List<CouponTemplateVO>> listCouponTemplates() {
        List<CouponTemplateVO> list = couponService.listActiveCoupons();
        return Result.ok(list);
    }

    // =========================================================
    // 秒杀抢券（需登录）
    // =========================================================

    /**
     * 参与秒杀抢券（核心接口，需登录）。
     *
     * <p>核心流程：
     * <ol>
     *   <li>校验场次合法性（存在、ACTIVE、在时间窗内）</li>
     *   <li>Redis Lua 脚本原子性执行：判重 + 判库存 + 预扣</li>
     *   <li>预扣成功 → 同步写 DB（后续升级为 MQ 异步）</li>
     * </ol>
     *
     * <p>请求体示例：
     * <pre>{@code
     * POST /api/v1/seckill
     * Authorization: Bearer {token}
     *
     * {
     *   "sessionId": "1234567890",
     *   "couponTemplateId": "9876543210"
     * }
     * }</pre>
     *
     * <p>错误场景：
     * <ul>
     *   <li>场次未开始 → SECKILL_NOT_STARTED (400)</li>
     *   <li>场次已结束 → SECKILL_EXPIRED (400)</li>
     *   <li>库存不足 → COUPON_STOCK_EXHAUSTED (400)</li>
     *   <li>已领取过 → COUPON_ALREADY_RECEIVED (400)</li>
     * </ul>
     *
     * @param request 秒杀请求体（sessionId + couponTemplateId）
     * @return 秒杀结果（success=true 表示抢到）
     */
    // 同一用户 5 秒内最多抢 1 次（防重复点击 & 防机器刷券）
    @RateLimit(key = "seckill:do", limit = 1, window = 5, keyType = RateLimit.KeyType.USER)
    @PostMapping("/api/v1/seckill")
    public Result<SeckillResultVO> doSeckill(@Valid @RequestBody SeckillRequest request) {
        SeckillResultVO vo = seckillService.doSeckill(request);
        return Result.ok(vo);
    }

    /**
     * 查询秒杀结果（异步场景下的轮询接口，需登录）。
     *
     * <p>当前阶段（同步写 DB）：POST /seckill 返回后即可知道结果，此接口用于补充查询。
     * 升级到 MQ 异步后：POST /seckill 发 MQ 即返回（结果未知），
     * 前端每隔 1 秒轮询此接口直到拿到确定结果。
     *
     * @param sessionId        秒杀场次 ID
     * @param couponTemplateId 券模板 ID
     * @return 抢购结果
     */
    @GetMapping("/api/v1/seckill/result")
    public Result<SeckillResultVO> querySeckillResult(
            @RequestParam @Positive Long sessionId,
            @RequestParam @Positive Long couponTemplateId) {
        SeckillResultVO vo = seckillService.querySeckillResult(sessionId, couponTemplateId);
        return Result.ok(vo);
    }

    // =========================================================
    // 我的券包（需登录）
    // =========================================================

    /**
     * 查询当前用户的所有优惠券（我的券包，需登录）。
     *
     * <p>返回所有状态（UNUSED / USED / EXPIRED）的券，前端分 Tab 展示。
     * 按领取时间倒序排列，最新领到的排前面。
     *
     * @return 用户持有的所有券列表
     */
    @GetMapping("/api/v1/coupons")
    public Result<List<UserCoupon>> listMyCoupons() {
        // 当前返回 Entity 列表（生产级应该返回 UserCouponVO，此处简化）
        // 后续可以扩展 UserCouponVO，包含券名称、门店名、折扣等冗余信息
        List<UserCoupon> coupons = seckillService.listMyCoupons();
        return Result.ok(coupons);
    }
}
