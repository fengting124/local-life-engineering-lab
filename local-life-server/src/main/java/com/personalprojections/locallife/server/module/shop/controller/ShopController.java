package com.personalprojections.locallife.server.module.shop.controller;

import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.shop.dto.CreateShopRequest;
import com.personalprojections.locallife.server.module.shop.dto.ShopVO;
import com.personalprojections.locallife.server.module.shop.dto.UpdateShopRequest;
import com.personalprojections.locallife.server.module.shop.service.ShopService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 门店模块 Controller。
 *
 * <h2>接口列表</h2>
 * <pre>
 *   GET    /api/v1/shops                      搜索门店列表（公开，C 端）
 *   GET    /api/v1/shops/{shopId}              门店详情（公开，C 端）
 *   POST   /api/v1/shops                      创建门店（需 APPROVED 商家）
 *   PUT    /api/v1/shops/{shopId}             更新门店信息（需 APPROVED 商家 + 归属校验）
 *   PUT    /api/v1/shops/{shopId}/status/online   门店上线（DRAFT/OFFLINE → ONLINE）
 *   PUT    /api/v1/shops/{shopId}/status/offline  门店下线（ONLINE → OFFLINE）
 * </pre>
 *
 * <h2>公开接口 vs 鉴权接口</h2>
 * <p>C 端查询接口（searchShops、getShopDetail）已在 WebMvcConfig 中加入白名单，
 * 无需登录即可访问。写操作接口需要商家身份，由 AuthInterceptor 强制检查 Token，
 * 再由 ShopService 内部调用 MerchantService.requireApprovedMerchant() 做商家身份校验。
 *
 * <h2>状态变更接口为什么用 PUT 而不是 POST</h2>
 * <p>状态变更（上线/下线）是对资源状态的「更新」操作，用 PUT 更符合 RESTful 语义。
 * 路径 /shops/{id}/status/online 明确表达「将门店状态设置为 online」，
 * 比 POST /shops/{id}/online 的「触发上线动作」语义更清晰。
 * 两种风格均可接受，此处选择 PUT + 子资源路径风格，面试时能说清原因即可。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/shops")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;

    // =========================================================
    // 公开查询接口（C 端，无需登录）
    // =========================================================

    /**
     * 搜索门店列表（公开，C 端）。
     *
     * <p>当前实现：按分类筛选，按评分降序排列。
     * 后续接入 Elasticsearch 后，此接口会支持关键词搜索、地理位置排序等能力，
     * 但接口签名（URL、参数名）不变，调用方无感知升级。
     *
     * <p>请求示例：
     * <pre>{@code
     * GET /api/v1/shops               -- 查所有分类的上线门店
     * GET /api/v1/shops?categoryId=1  -- 只查分类 1 的上线门店
     * }</pre>
     *
     * <p>响应体示例：
     * <pre>{@code
     * {
     *   "code": "OK",
     *   "data": [
     *     {
     *       "shopId": "1234567890",
     *       "shopName": "张三的火锅店",
     *       "coverImage": "https://cdn.example.com/cover.jpg",
     *       "score": 4.8,
     *       "address": "北京市朝阳区三里屯",
     *       "status": "ONLINE"
     *     }
     *   ]
     * }
     * }</pre>
     *
     * @param categoryId 分类 ID（可选），不传则查所有分类
     * @return ONLINE 状态的门店列表
     */
    @GetMapping
    public Result<List<ShopVO>> searchShops(
            @RequestParam(required = false) Integer categoryId) {
        List<ShopVO> shops = shopService.searchShops(categoryId);
        return Result.ok(shops);
    }

    /**
     * 查询门店详情（公开，C 端）。
     *
     * <p>只返回 ONLINE 状态的门店。若门店不存在或状态不是 ONLINE，
     * 统一返回 SHOP_NOT_FOUND（不区分两种情况，防止竞品枚举未上线门店信息）。
     *
     * <p>请求示例：
     * <pre>{@code
     * GET /api/v1/shops/1234567890
     * }</pre>
     *
     * <p>错误场景：
     * <ul>
     *   <li>门店不存在 → SHOP_NOT_FOUND (400)</li>
     *   <li>门店存在但状态为 DRAFT/OFFLINE/CLOSED → SHOP_NOT_FOUND (400)（故意不区分）</li>
     * </ul>
     *
     * @param shopId 门店 ID（路径参数）
     * @return 门店详情 VO
     */
    @GetMapping("/{shopId}")
    public Result<ShopVO> getShopDetail(@PathVariable Long shopId) {
        ShopVO vo = shopService.getShopDetail(shopId);
        return Result.ok(vo);
    }

    // =========================================================
    // 商家操作接口（需登录 + APPROVED 商家身份）
    // =========================================================

    /**
     * 创建门店（商家操作）。
     *
     * <p>前置条件：
     * <ol>
     *   <li>已登录（AuthInterceptor 校验 Token）</li>
     *   <li>当前用户是 APPROVED 状态的商家（ShopService 内部校验）</li>
     * </ol>
     *
     * <p>创建后门店状态为 DRAFT，不对 C 端可见。
     * 商家需要手动调用上线接口（PUT /shops/{id}/status/online）才能对外展示。
     *
     * <p>请求体示例：
     * <pre>{@code
     * POST /api/v1/shops
     * Authorization: Bearer {token}
     * Content-Type: application/json
     *
     * {
     *   "shopName": "张三的火锅店",
     *   "categoryId": 1,
     *   "description": "正宗川味火锅",
     *   "address": "北京市朝阳区三里屯 123 号",
     *   "longitude": 116.4551,
     *   "latitude": 39.9373,
     *   "phone": "010-12345678",
     *   "businessHours": "周一至周日 11:00-23:00"
     * }
     * }</pre>
     *
     * <p>错误场景：
     * <ul>
     *   <li>未登录 → AUTH_TOKEN_MISSING (401)</li>
     *   <li>未申请商家 → MERCHANT_NOT_FOUND (400)</li>
     *   <li>商家审核未通过 → MERCHANT_NOT_APPROVED (403)</li>
     *   <li>请求参数不合法 → SYS_PARAM_INVALID (400)</li>
     * </ul>
     *
     * @param request 创建请求体（@Valid 触发字段校验）
     * @return 创建后的门店 VO（status = DRAFT）
     */
    @PostMapping
    public Result<ShopVO> createShop(@Valid @RequestBody CreateShopRequest request) {
        ShopVO vo = shopService.createShop(request);
        return Result.ok(vo);
    }

    /**
     * 更新门店信息（商家操作，全量更新 PUT 语义）。
     *
     * <p>前置条件：
     * <ol>
     *   <li>已登录</li>
     *   <li>当前用户是 APPROVED 商家</li>
     *   <li>目标门店属于当前商家</li>
     *   <li>门店不是 CLOSED 状态（终态，不可修改）</li>
     * </ol>
     *
     * <p>全量更新：所有字段必须传，未传的可选字段会被置为空字符串。
     * 不能通过此接口修改的字段：merchantId（不能转让）、status（通过专门的上线/下线接口）、score（后台任务计算）。
     *
     * <p>错误场景：
     * <ul>
     *   <li>门店不属于当前商家（含门店不存在） → SHOP_FORBIDDEN (403)</li>
     *   <li>门店已永久关闭 → SHOP_STATUS_ILLEGAL (400)</li>
     * </ul>
     *
     * @param shopId  目标门店 ID（路径参数）
     * @param request 更新请求体（全量字段）
     * @return 更新后的门店 VO
     */
    @PutMapping("/{shopId}")
    public Result<ShopVO> updateShop(
            @PathVariable Long shopId,
            @Valid @RequestBody UpdateShopRequest request) {
        ShopVO vo = shopService.updateShop(shopId, request);
        return Result.ok(vo);
    }

    // =========================================================
    // 门店状态变更接口
    // =========================================================

    /**
     * 门店上线（DRAFT 或 OFFLINE → ONLINE）。
     *
     * <p>上线后，C 端用户可以通过搜索或直链访问该门店。
     *
     * <p>合法的前置状态：
     * <ul>
     *   <li>DRAFT（新建后首次上线）</li>
     *   <li>OFFLINE（下线后恢复上线）</li>
     * </ul>
     *
     * <p>路径设计：PUT /shops/{id}/status/online
     * <ul>
     *   <li>/status 明确表示这是对状态子资源的操作</li>
     *   <li>online 是目标状态值，PUT 表示「设置为 online」</li>
     *   <li>对应下线：PUT /shops/{id}/status/offline，对称一致</li>
     * </ul>
     *
     * <p>错误场景：
     * <ul>
     *   <li>门店不属于当前商家 → SHOP_FORBIDDEN (403)</li>
     *   <li>门店当前状态不允许上线（如已 ONLINE 或已 CLOSED）→ SHOP_STATUS_ILLEGAL (400)</li>
     * </ul>
     *
     * @param shopId 目标门店 ID
     * @return 更新状态后的门店 VO（status = ONLINE）
     */
    @PutMapping("/{shopId}/status/online")
    public Result<ShopVO> onlineShop(@PathVariable Long shopId) {
        ShopVO vo = shopService.onlineShop(shopId);
        return Result.ok(vo);
    }

    /**
     * 门店下线（ONLINE → OFFLINE）。
     *
     * <p>下线后，C 端搜索和详情页将不再展示该门店，但数据不会删除。
     * 商家可以在管理后台继续编辑，并在需要时重新上线。
     *
     * <p>合法的前置状态：仅 ONLINE。
     * （DRAFT 状态的门店还未上线过，无需执行下线操作；CLOSED 是终态，不可操作）
     *
     * <p>错误场景：
     * <ul>
     *   <li>门店不属于当前商家 → SHOP_FORBIDDEN (403)</li>
     *   <li>门店当前状态不是 ONLINE（无法下线） → SHOP_STATUS_ILLEGAL (400)</li>
     * </ul>
     *
     * @param shopId 目标门店 ID
     * @return 更新状态后的门店 VO（status = OFFLINE）
     */
    @PutMapping("/{shopId}/status/offline")
    public Result<ShopVO> offlineShop(@PathVariable Long shopId) {
        ShopVO vo = shopService.offlineShop(shopId);
        return Result.ok(vo);
    }
}
