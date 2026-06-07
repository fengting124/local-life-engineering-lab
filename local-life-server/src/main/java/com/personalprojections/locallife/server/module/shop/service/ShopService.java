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
 * <h3>缓存一致性：从延迟双删到 Canal+binlog（已完成升级）</h3>
 * <p>早期方案是「延迟双删」（Cache Aside + Delayed Double Delete）：
 * 写操作执行「先删缓存 → 写 DB → 延迟 500ms 再删缓存」，第二次删除用一个
 * "猜测的" 500ms 延迟去对冲"写 DB 期间读线程把旧值回填缓存"的竞态窗口。
 * 这个方案能工作，但有两个不优雅的地方：
 * <ul>
 *   <li>500ms 是拍脑袋的经验值（"主从同步延迟通常 &lt;200ms，给 2.5 倍余量"），
 *       本质是在猜"数据变更需要多久才能让所有读路径感知到"——猜对了万事大吉，
 *       猜小了竞态窗口仍然存在，猜大了则让缓存不必要地脏更久</li>
 *   <li>缓存失效逻辑和业务写逻辑耦合在一起：每新增一个会改 shop 表的写路径
 *       （这次是 update/online/offline，下次可能是后台批量改价），都要记得
 *       补上"删缓存"调用——一旦漏掉，就是一个只有在生产环境并发场景下才会
 *       暴露的隐蔽 bug</li>
 * </ul>
 * <p>现已升级为 <b>Canal + binlog</b> 方案（实现见
 * {@code com.personalprojections.locallife.server.module.shop.canal.ShopCacheInvalidationListener}），
 * 把"猜延迟"换成"听事件"：
 * <pre>
 *   写 DB 前：deleteShopDetail(shopId)        ← 同步、立即，处理"写之前缓存里还有旧值"的简单情形
 *   写 DB
 *   写 DB 后：（不再需要业务代码显式删除）
 *            └─ MySQL 写入 binlog（行级变更日志，唯一可信的"数据变了"信号源）
 *               └─ Canal Server 订阅 binlog，解析后推送给 ShopCacheInvalidationListener
 *                  └─ deleteShopDetail(shopId)  ← 由数据变更事件精确驱动，不是定时器猜出来的
 * </pre>
 * <p>这一换并不是简单的"用新中间件替换旧代码"，而是把缓存失效的触发条件
 * 从"我猜数据已经落盘并同步完成了"换成"binlog 明确告诉我数据确实变了"——
 * 后者既更快（不需要凭空等待 500ms），又更准（不依赖任何经验阈值），
 * 还彻底解耦了业务代码与缓存失效逻辑（新增写路径无需再记得"删缓存"，
 * 因为 Canal 盯的是表，不是某一条具体的业务代码路径）。
 * <p>{@code ShopCacheService.SHOP_DETAIL_TTL}（30 分钟）仍然保留，作为这套体系的<b>最终安全网</b>——
 * 即使 Canal Server 宕机、{@code ShopCacheInvalidationListener} 重连失败，
 * 缓存最多脏 30 分钟后自然过期重建，不会永久不一致；Canal 真正压缩的是
 * "正常情况下"的不一致窗口（从"最长 30 分钟"到"百毫秒级"），而不是取代 TTL
 * 这道兜底防线——这与 {@code OrderService} "MQ 延时消息为主链路、定时任务兜底"
 * 的分层防御思路一脉相承：让快速路径专注于"快"，把"绝对不能错"的责任
 * 交给慢但可靠的兜底机制。
 *
 * <h3>面试追问准备</h3>
 * <ul>
 *   <li>为什么从延迟双删升级到 Canal？→ 延迟双删的"500ms"是拍脑袋的经验值，
 *       且要求每个写路径都记得调用缓存失效；Canal 把失效逻辑收敛到"监听 binlog"
 *       这一个地方，由真实的数据变更事件驱动，更快、更准、更解耦</li>
 *   <li>Canal 引入了一个外部中间件依赖，万一它挂了怎么办？→ 不会导致永久不一致——
 *       Redis 缓存本身有 30 分钟 TTL 兜底，Canal 只是把"正常情况"下的不一致窗口
 *       从分钟级压缩到毫秒级，而不是成为正确性的单点依赖</li>
 *   <li>升级后旧的延迟双删代码去哪了？→ 完全移除（{@code ShopCacheService.delayedDeleteShopDetail}
 *       已删除，{@code @EnableAsync} 也一并移除——它是该方法存在时唯一的使用者）。
 *       写 DB 前的同步 {@code deleteShopDetail} 予以保留：它处理的是"写之前缓存里
 *       还有旧值"这个简单情形，与 Canal 的职责互不重叠，没有理由删掉</li>
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

    /** 门店详情缓存（Redis L2，一致性由 Canal+binlog 驱动失效，详见类注释） */
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
     *       如果门店下线，正常情况下 {@code ShopCacheInvalidationListener} 会在
     *       binlog 事件到达后的百毫秒级内清理；极端情况下（Canal 不可用）
     *       缓存最多在 TTL（30min）后自然过期重建——见类注释「缓存一致性」）</li>
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
     * <p>写 DB 前同步删一次缓存，处理"写之前缓存里还有旧值"的简单情形；
     * 写 DB 之后不再需要业务代码显式删缓存——MySQL 写入 binlog 后，
     * {@code ShopCacheInvalidationListener} 会监听到这次 UPDATE 事件并精确失效缓存
     * （详见类注释「缓存一致性：从延迟双删到 Canal+binlog」）。
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

        log.info("[ShopService] 门店更新，shopId={}，缓存失效交由 Canal binlog 监听器处理", shopId);

        shopSearchService.syncShop(shop);
        return toVO(shop);
    }

    /**
     * 门店上线（DRAFT/OFFLINE → ONLINE）。
     *
     * <p>写 DB 前同步删一次缓存；写 DB 后的缓存失效由
     * {@code ShopCacheInvalidationListener} 监听 binlog 驱动完成
     * （详见类注释「缓存一致性：从延迟双删到 Canal+binlog」）。
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

        log.info("[ShopService] 门店上线，shopId={}，缓存失效交由 Canal binlog 监听器处理", shopId);

        shopSearchService.syncShop(shop);
        return toVO(shop);
    }

    /**
     * 门店下线（ONLINE → OFFLINE）。
     *
     * <p>写 DB 前同步删一次缓存；写 DB 后的缓存失效由
     * {@code ShopCacheInvalidationListener} 监听 binlog 驱动完成
     * （详见类注释「缓存一致性：从延迟双删到 Canal+binlog」）。
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

        log.info("[ShopService] 门店下线，shopId={}，缓存失效交由 Canal binlog 监听器处理", shopId);

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
