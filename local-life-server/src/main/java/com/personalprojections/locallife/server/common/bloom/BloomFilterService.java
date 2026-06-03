package com.personalprojections.locallife.server.common.bloom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 布隆过滤器服务，用于防止缓存穿透。
 *
 * <h2>解决的问题</h2>
 * <p>当用户传入不存在的 shopId（如恶意爬虫遍历 ID），请求会穿透缓存直接打到 MySQL。
 * 布隆过滤器在缓存之前做一次「可能存在」的判断：
 * <ul>
 *   <li>返回 false → 一定不存在，直接拒绝，不查 DB</li>
 *   <li>返回 true  → 可能存在（有 1% 误判率），继续查缓存/DB</li>
 * </ul>
 *
 * <h2>参数选择依据</h2>
 * <ul>
 *   <li>EXPECTED_INSERTIONS = 100万：预估平台最大门店数量，保留足够扩展空间</li>
 *   <li>FALSE_PROBABILITY = 0.01（1%）：误判率 1% 意味着 100 个不存在的 ID 中约有 1 个通过，
 *       可接受的代价；更低误判率（0.001）需要更多 Redis 内存</li>
 *   <li>内存占用估算：100万期望插入 + 1% 误判率 ≈ 9.6 bit/element × 100万 ≈ 1.2 MB（Redis BitSet）</li>
 * </ul>
 *
 * <h2>启动时从 DB 预加载</h2>
 * <p>{@code @PostConstruct} 时查所有 shop.id 批量 add 到布隆过滤器。
 * 这样即使 Redis 重启（布隆过滤器数据丢失），下次启动会重新加载。
 *
 * <h2>布隆过滤器不支持删除的处理</h2>
 * <p>Redisson {@code RBloomFilter} 基于标准布隆过滤器，不支持删除单个元素。
 * 当门店被关闭（CLOSED）后，布隆过滤器里仍有记录，会有少量 false positive，
 * 但后续缓存会命中「空值」（null marker），不会打到 DB，可接受。
 * 如果需要支持删除，可升级为 Counting Bloom Filter，代价是内存增加约 3-4 倍。
 *
 * <h2>面试追问准备</h2>
 * <ul>
 *   <li>为什么不只缓存空值？空值 TTL 短（5min），高并发仍会有穿透窗口；布隆过滤器是 O(1) 前置拦截</li>
 *   <li>Redis 宕机怎么办？重启时 @PostConstruct 重建，约需几秒（100万 ID 批量 add）</li>
 *   <li>新门店创建后是否实时生效？是，createShop 后调 addShopId() 即时注册</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloomFilterService {

    /** 预期最大门店数量，用于计算布隆过滤器 bit 数组大小 */
    private static final long EXPECTED_INSERTIONS = 1_000_000L;

    /** 误判率 1%：100 个不存在的 shopId 中约 1 个会通过过滤器（可接受的代价） */
    private static final double FALSE_PROBABILITY = 0.01D;

    private static final String SHOP_BLOOM_NAME = "bloom:shop:id";

    private final RedissonClient redissonClient;

    /**
     * ShopMapper 注入用于启动时从 DB 加载全量门店 ID。
     * 只用于 @PostConstruct，运行时不再查询。
     */
    private final ShopMapper shopMapper;

    private RBloomFilter<Long> shopBloomFilter;

    /**
     * 应用启动时初始化布隆过滤器，并从 DB 批量加载现有门店 ID。
     *
     * <p>tryInit 是幂等的：如果 Redis 中已有同名过滤器且参数相同，直接复用；
     * 参数不同时会抛异常（需要先删除旧 key）。
     */
    @PostConstruct
    public void init() {
        shopBloomFilter = redissonClient.getBloomFilter(SHOP_BLOOM_NAME);
        // tryInit 幂等：Redis 中已有同名布隆过滤器时直接复用，不重置
        boolean created = shopBloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);

        if (created) {
            // 新建的过滤器：从 DB 加载所有现有门店 ID
            loadExistingShopIds();
        } else {
            // 已存在的过滤器（Redis 未重启）：复用，只记录日志
            long count = shopBloomFilter.count();
            log.info("[Bloom] 复用已有 shop bloom filter, count≈{}", count);
        }
    }

    /**
     * 新增门店时调用，将门店 ID 加入布隆过滤器。
     * 创建门店后立即调用，确保新门店可以被查询到。
     */
    public void addShopId(Long shopId) {
        if (shopId != null) {
            shopBloomFilter.add(shopId);
        }
    }

    /**
     * 判断 shopId 是否可能存在。
     *
     * @return false → 一定不存在（可直接返回 SHOP_NOT_FOUND，不查 DB）
     *         true  → 可能存在（需继续查缓存/DB；有 1% 误判率）
     */
    public boolean mightContainShopId(Long shopId) {
        return shopId != null && shopBloomFilter.contains(shopId);
    }

    // =========================================================
    // 私有方法
    // =========================================================

    /**
     * 从 DB 批量加载所有门店 ID 到布隆过滤器（仅在首次初始化时执行）。
     * 只查 id 列，不查全部字段，降低内存和查询开销。
     */
    private void loadExistingShopIds() {
        log.info("[Bloom] 正在从 DB 加载门店 ID 到布隆过滤器...");
        long start = System.currentTimeMillis();

        // 只查 id 字段，不查其他列（性能优化：减少数据传输量）
        List<Shop> shops = shopMapper.selectList(
                new LambdaQueryWrapper<Shop>().select(Shop::getId)
        );

        if (!shops.isEmpty()) {
            shops.stream()
                    .map(Shop::getId)
                    .filter(id -> id != null)
                    .forEach(shopBloomFilter::add);
        }

        long cost = System.currentTimeMillis() - start;
        log.info("[Bloom] 布隆过滤器初始化完成，加载 {} 个门店 ID，耗时 {}ms，误判率={}",
                shops.size(), cost, FALSE_PROBABILITY);
    }
}
