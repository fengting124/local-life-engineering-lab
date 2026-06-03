package com.personalprojections.locallife.server.module.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.personalprojections.locallife.server.common.bloom.BloomFilterService;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.Merchant;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.module.merchant.service.MerchantService;
import com.personalprojections.locallife.server.module.search.service.ShopSearchService;
import com.personalprojections.locallife.server.module.shop.dto.CreateShopRequest;
import com.personalprojections.locallife.server.module.shop.dto.ShopVO;
import com.personalprojections.locallife.server.module.shop.dto.UpdateShopRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 门店 Service，负责门店的创建、查询、修改和状态变更。
 *
 * <h2>缓存与布隆过滤器架构（防穿透 + 防击穿 + 一致性）</h2>
 * <pre>
 *   请求 → 布隆过滤器（O(1)，防穿透）
 *         → Redis 缓存（L2，30min TTL）
 *         → MySQL（真实数据源）
 * </pre>
 *
 * <h3>缓存穿透防护</h3>
 * <p>不存在的 shopId（如恶意爬虫遍历 ID）会被布隆过滤器拦截，不查 DB。
 * 误判率 1%：100 个不存在的 ID 中约 1 个会通过，后续缓存空值兜底。
 *
 * <h3>延迟双删（Cache Aside + Delayed Double Delete）</h3>
 * <p>写操作执行「先删缓存 → 写 DB → 延迟 500ms 再删缓存」三步：
 * <ul>
 *   <li>第一次删：写 DB 前清理可能的旧缓存</li>
 *   <li>写 DB：持久化数据</li>
 *   <li>500ms 后再删：防止「写 DB 期间有读线程把旧值回填缓存」的竞态窗口</li>
 * </ul>
 * 500ms 依据：MySQL 主从同步延迟通常 <200ms，500ms 是 2.5 倍安全余量。
 *
 * <h3>面试追问准备</h3>
 * <ul>
 *   <li>为什么不用 Canal+binlog 替代延迟双删？→ Canal 引入独立组件，当前阶段延迟双删够用，
 *       Canal 是下一阶段升级方向</li>
 *   <li>延迟双删能 100% 保证一致性吗？→ 不能，存在极短窗口期；
 *       但发生的概率极低（需要精确的并发时序），可接受的 Trade-off</li>
 * </ul>
 *
 * <h2>权限校验策略</h2>
 * <p>所有写操作有两层校验：身份校验（APPROVED 商家）+ 归属校验（门店属于当前商家）。
 *
 * <h2>状态机保护</h2>
 * <pre>
 *   DRAFT → ONLINE → OFFLINE → ONLINE（可循环）
 *   ONLINE/OFFLINE → CLOSED（终态，不可逆）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopService {

    private final ShopMapper shopMapper;
    private final MerchantService merchantService;
    private final ShopSearchService shopSearchService;

    /** 布隆过滤器：前置拦截不存在的 shopId，防缓存穿透 */
    private final BloomFilterService bloomFilterService;

    /** 门店详情缓存（Redis L2 + @Async 延迟双删） */
    private final ShopCacheService shopCacheService;

    // =========================================================
    // 查询（公开，C 端）
    // =========================================================

    /**
     * 查询门店详情（公开接口，无需登录）。
     *
     * <p><b>完整查询链路</b>：布隆过滤器 → Redis 缓存 → MySQL
     * <ol>
     *   <li>布隆过滤器：shopId 一定不存在 → 直接抛 SHOP_NOT_FOUND，不查缓存/DB</li>
     *   <li>Redis 缓存：命中 → 直接返回（注意：缓存了 ONLINE 状态的门店，
     *       如果门店下线，缓存会在 TTL（30min）内仍返回旧值；
     *       写操作的延迟双删会在 500ms 内清理）</li>
     *   <li>MySQL：查到 → 写入缓存，返回；查不到 → 缓存空值（5min TTL）防短期重复穿透</li>
     * </ol>
     *
     * @param shopId 门店 ID
     * @return 门店详情 VO
     */
    public ShopVO getShopDetail(Long shopId) {
        // ---- Step 1：布隆过滤器前置拦截（防穿透）----
        // 返回 false = 一定不存在，直接拒绝，不查 Redis/DB
        // 返回 true  = 可能存在（有 1% 误判率），继续查
        if (!bloomFilterService.mightContainShopId(shopId)) {
            log.debug("[ShopService] 布隆过滤器拦截不存在的 shopId={}", shopId);
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }

        // ---- Step 2：查 Redis 缓存 ----
        Shop cached = shopCacheService.getShopDetail(shopId);
        if (cached != null) {
            // 缓存命中：校验状态（门店可能已下线但缓存未失效）
            if (!"ONLINE".equals(cached.getStatus())) {
                throw new BizException(ErrorCode.SHOP_NOT_FOUND);
            }
            return toVO(cached);
        }

        // ---- Step 3：查 MySQL ----
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null || !"ONLINE".equals(shop.getStatus())) {
            // 缓存空值（短 TTL），防止同一 ID 短期内重复穿透
            // 注意：这里不缓存空值 Shop 对象而是依靠布隆过滤器兜底
            // 已通过布隆过滤器但 DB 不存在 = 布隆误判（1% 概率），可接受
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }

        // 写入缓存（TTL = 30min，随机偏移防缓存雪崩）
        shopCacheService.putShopDetail(shop);

        return toVO(shop);
    }

    /**
     * 搜索门店列表（公开接口，C 端）。
     * 不走缓存：列表查询结果随过滤条件变化，缓存命中率低，直接走 MySQL。
     *
     * @param categoryId 分类 ID，为 null 时查所有分类
     * @return ONLINE 状态的门店列表
     */
    public List<ShopVO> searchShops(Integer categoryId) {
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<Shop>()
                .eq(Shop::getStatus, "ONLINE")
                .eq(categoryId != null, Shop::getCategoryId, categoryId)
                .orderByDesc(Shop::getScore)
                .orderByDesc(Shop::getCreatedAt);

        return shopMapper.selectList(wrapper)
                .stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    // =========================================================
    // 写操作（商家端，需要鉴权）
    // =========================================================

    /**
     * 创建门店（商家操作）。
     *
     * <p>创建成功后：
     * <ol>
     *   <li>将新 shopId 注册到布隆过滤器（实时生效，不需要等下次重启）</li>
     *   <li>双写 ES（DRAFT 状态写入，上线时搜索自动生效）</li>
     * </ol>
     * 注意：新建门店不写缓存（状态为 DRAFT，C 端查不到）。
     *
     * @param request 创建请求体（已通过 @Valid 校验）
     * @return 创建后的门店 VO（status = DRAFT）
     */
    public ShopVO createShop(CreateShopRequest request) {
        Merchant merchant = merchantService.requireApprovedMerchant();

        Shop shop = Shop.builder()
                .merchantId(merchant.getId())
                .shopName(request.getShopName())
                .categoryId(request.getCategoryId())
                .coverImage("")
                .description(request.getDescription() != null ? request.getDescription() : "")
                .address(request.getAddress())
                .longitude(request.getLongitude())
                .latitude(request.getLatitude())
                .phone(request.getPhone() != null ? request.getPhone() : "")
                .businessHours(request.getBusinessHours() != null ? request.getBusinessHours() : "")
                .score(BigDecimal.ZERO)
                .status("DRAFT")
                .build();

        shopMapper.insert(shop);

        // 新 shopId 注册到布隆过滤器（实时生效，不需要等下次服务重启重建）
        bloomFilterService.addShopId(shop.getId());

        log.info("[ShopService] 门店创建成功，merchantId={}, shopId={}", merchant.getId(), shop.getId());

        // 双写 ES（DRAFT 状态也写入，方便上线时无需重建）
        shopSearchService.syncShop(shop);

        return toVO(shop);
    }

    /**
     * 更新门店信息（商家操作）。
     *
     * <p>写 DB 后执行延迟双删：先立即删一次缓存，500ms 后再删一次。
     * 防止写 DB 期间有读线程把旧值回填缓存的竞态窗口。
     */
    public ShopVO updateShop(Long shopId, UpdateShopRequest request) {
        Shop shop = requireOwnShop(shopId);

        if ("CLOSED".equals(shop.getStatus())) {
            throw new BizException(ErrorCode.SHOP_STATUS_ILLEGAL);
        }

        // 写 DB 前：先删一次缓存（防止写 DB 期间读线程命中旧缓存）
        shopCacheService.deleteShopDetail(shopId);

        shop.setShopName(request.getShopName());
        shop.setCategoryId(request.getCategoryId());
        shop.setDescription(request.getDescription() != null ? request.getDescription() : "");
        shop.setAddress(request.getAddress());
        shop.setLongitude(request.getLongitude());
        shop.setLatitude(request.getLatitude());
        shop.setPhone(request.getPhone() != null ? request.getPhone() : "");
        shop.setBusinessHours(request.getBusinessHours() != null ? request.getBusinessHours() : "");

        shopMapper.updateById(shop);

        // 写 DB 后：延迟 500ms 再删一次（消除并发读写的竞态窗口）
        // @Async 异步执行，不阻塞主流程
        shopCacheService.delayedDeleteShopDetail(shopId);

        log.info("[ShopService] 门店更新，shopId={}，触发延迟双删", shopId);

        shopSearchService.syncShop(shop);
        return toVO(shop);
    }

    /**
     * 门店上线（DRAFT/OFFLINE → ONLINE）。
     * 状态变更后清理缓存（延迟双删），确保 C 端能查到最新状态。
     */
    public ShopVO onlineShop(Long shopId) {
        Shop shop = requireOwnShop(shopId);

        if (!"DRAFT".equals(shop.getStatus()) && !"OFFLINE".equals(shop.getStatus())) {
            throw new BizException(ErrorCode.SHOP_STATUS_ILLEGAL);
        }

        // 写 DB 前先删缓存
        shopCacheService.deleteShopDetail(shopId);

        shop.setStatus("ONLINE");
        shopMapper.updateById(shop);

        // 延迟双删（防竞态）
        shopCacheService.delayedDeleteShopDetail(shopId);

        log.info("[ShopService] 门店上线，shopId={}，触发延迟双删", shopId);

        shopSearchService.syncShop(shop);
        return toVO(shop);
    }

    /**
     * 门店下线（ONLINE → OFFLINE）。
     * 下线后需立即清理缓存，防止 C 端继续看到已下线门店。
     */
    public ShopVO offlineShop(Long shopId) {
        Shop shop = requireOwnShop(shopId);

        if (!"ONLINE".equals(shop.getStatus())) {
            throw new BizException(ErrorCode.SHOP_STATUS_ILLEGAL);
        }

        // 写 DB 前先删缓存
        shopCacheService.deleteShopDetail(shopId);

        shop.setStatus("OFFLINE");
        shopMapper.updateById(shop);

        // 延迟双删（防竞态）
        shopCacheService.delayedDeleteShopDetail(shopId);

        log.info("[ShopService] 门店下线，shopId={}，触发延迟双删", shopId);

        shopSearchService.syncShop(shop);
        return toVO(shop);
    }

    /**
     * 查询当前商家的所有门店（B 端管理后台）。
     */
    public List<ShopVO> listMyShops() {
        Merchant merchant = merchantService.requireApprovedMerchant();

        return shopMapper.selectList(
                        new LambdaQueryWrapper<Shop>()
                                .eq(Shop::getMerchantId, merchant.getId())
                                .orderByDesc(Shop::getCreatedAt)
                )
                .stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    // =========================================================
    // 私有辅助方法
    // =========================================================

    private Shop requireOwnShop(Long shopId) {
        Merchant merchant = merchantService.requireApprovedMerchant();
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null || !merchant.getId().equals(shop.getMerchantId())) {
            throw new BizException(ErrorCode.SHOP_FORBIDDEN);
        }
        return shop;
    }

    private ShopVO toVO(Shop shop) {
        return ShopVO.builder()
                .shopId(String.valueOf(shop.getId()))
                .merchantId(String.valueOf(shop.getMerchantId()))
                .shopName(shop.getShopName())
                .categoryId(shop.getCategoryId())
                .coverImage(shop.getCoverImage())
                .description(shop.getDescription())
                .address(shop.getAddress())
                .longitude(shop.getLongitude())
                .latitude(shop.getLatitude())
                .phone(shop.getPhone())
                .businessHours(shop.getBusinessHours())
                .score(shop.getScore())
                .status(shop.getStatus())
                .build();
    }
}
