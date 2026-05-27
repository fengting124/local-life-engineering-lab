package com.personalprojections.locallife.server.module.merchant.controller;

import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.merchant.dto.ApplyMerchantRequest;
import com.personalprojections.locallife.server.module.merchant.dto.MerchantVO;
import com.personalprojections.locallife.server.module.merchant.service.MerchantService;
import com.personalprojections.locallife.server.module.shop.dto.ShopVO;
import com.personalprojections.locallife.server.module.shop.service.ShopService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商家模块 Controller。
 *
 * <h2>接口列表</h2>
 * <pre>
 *   POST   /api/v1/merchants/apply      申请成为商家（需登录）
 *   GET    /api/v1/merchants/me         查询当前商家信息（需登录）
 *   GET    /api/v1/merchants/my-shops   查询当前商家名下所有门店（B 端，需 APPROVED 商家）
 * </pre>
 *
 * <h2>鉴权说明</h2>
 * <p>所有接口都需要登录（不在白名单中），AuthInterceptor 会检查 Authorization Header。
 * 商家操作（创建门店、修改门店）需要额外的商家身份校验，在 Service 层执行（不在此处）。
 *
 * <h2>为什么 my-shops 放在 MerchantController</h2>
 * <p>虽然 my-shops 查询的是门店数据，但它是「商家的视角」（查自己名下的门店），
 * 归属于商家资源（/merchants/my-shops），语义上比 /shops?mine=true 更清晰。
 * 具体查询逻辑委托给 ShopService，Controller 层只负责路由。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;

    /**
     * ShopService 用于「商家查询自己名下门店」，
     * 虽然跨模块调用，但 Controller 层只注入 Service，不直接操作 Mapper，符合分层规范。
     */
    private final ShopService shopService;

    // =========================================================
    // 商家申请
    // =========================================================

    /**
     * 申请成为商家。
     *
     * <p>业务规则：
     * <ol>
     *   <li>需要登录（鉴权拦截器强制检查 Token）</li>
     *   <li>同一用户只能申请一次（幂等接口：重复申请直接返回当前状态，不报错）</li>
     *   <li>当前阶段：提交即自动 APPROVED（模拟审核通过）</li>
     * </ol>
     *
     * <p>请求体示例：
     * <pre>{@code
     * POST /api/v1/merchants/apply
     * Authorization: Bearer {token}
     * Content-Type: application/json
     *
     * {
     *   "merchantName": "张三的火锅店",
     *   "description": "正宗川味火锅",
     *   "contactMobile": "13800138000"
     * }
     * }</pre>
     *
     * <p>响应体示例：
     * <pre>{@code
     * {
     *   "code": "OK",
     *   "message": "操作成功",
     *   "data": {
     *     "merchantId": "1234567890",
     *     "merchantName": "张三的火锅店",
     *     "logo": "",
     *     "description": "正宗川味火锅",
     *     "contactMobile": "138****8000",
     *     "status": "APPROVED"
     *   },
     *   "timestamp": "2026-05-26T20:00:00+08:00"
     * }
     * }</pre>
     *
     * @param request 申请请求体，@Valid 触发字段校验
     * @return 商家信息 VO（含脱敏手机号）
     */
    @PostMapping("/apply")
    public Result<MerchantVO> apply(@Valid @RequestBody ApplyMerchantRequest request) {
        MerchantVO vo = merchantService.apply(request);
        return Result.ok(vo);
    }

    // =========================================================
    // 商家信息查询
    // =========================================================

    /**
     * 查询当前登录用户的商家信息。
     *
     * <p>适用场景：商家管理后台首页，展示商家基本信息和审核状态。
     *
     * <p>响应体示例：
     * <pre>{@code
     * GET /api/v1/merchants/me
     * Authorization: Bearer {token}
     *
     * {
     *   "code": "OK",
     *   "data": {
     *     "merchantId": "1234567890",
     *     "merchantName": "张三的火锅店",
     *     "logo": "https://cdn.example.com/logo.jpg",
     *     "description": "正宗川味火锅",
     *     "contactMobile": "138****8000",
     *     "status": "APPROVED"
     *   }
     * }
     * }</pre>
     *
     * <p>错误场景：
     * <ul>
     *   <li>未申请过商家资质 → MERCHANT_NOT_FOUND (400)</li>
     * </ul>
     *
     * @return 当前用户的商家 VO
     */
    @GetMapping("/me")
    public Result<MerchantVO> getMyMerchant() {
        MerchantVO vo = merchantService.getMyMerchant();
        return Result.ok(vo);
    }

    // =========================================================
    // 商家的门店列表（B 端视角）
    // =========================================================

    /**
     * 查询当前商家名下的所有门店（B 端管理后台）。
     *
     * <p>与 C 端 {@code GET /api/v1/shops} 的区别：
     * <ul>
     *   <li>C 端只返回 ONLINE 状态的门店，无需登录</li>
     *   <li>B 端（此接口）返回当前商家的全部状态门店（含 DRAFT / OFFLINE / CLOSED）</li>
     * </ul>
     *
     * <p>商家可以在这里看到自己创建的所有门店，管理门店的上线/下线状态。
     *
     * <p>错误场景：
     * <ul>
     *   <li>未申请商家或审核未通过 → MERCHANT_NOT_APPROVED (403)</li>
     * </ul>
     *
     * @return 当前商家的门店列表（按创建时间倒序）
     */
    @GetMapping("/my-shops")
    public Result<List<ShopVO>> listMyShops() {
        List<ShopVO> shops = shopService.listMyShops();
        return Result.ok(shops);
    }
}
