package com.personalprojections.locallife.server.module.shop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.domain.entity.Shop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 门店详情 Redis 缓存封装。
 *
 * <h2>{@code SHOP_DETAIL_TTL} 是缓存一致性的最终安全网</h2>
 * <p>30 分钟 TTL 不只是"防止 Redis 内存无限增长"的常规设计，更是
 * {@code ShopService} 缓存一致性架构（详见其类注释「缓存一致性：从延迟双删到 Canal+binlog」）
 * 中"无论上游哪个环节失效，缓存最多脏多久"的兜底承诺——
 * 即便 {@code ShopCacheInvalidationListener} 长时间宕机、Canal Server 不可用，
 * 缓存也会在 30 分钟内自然过期并从 MySQL 重建，不会永久不一致。
 * 这正是 Canal 上线后<b>不需要</b>把 TTL 缩短的原因：它压缩的是"正常情况下"的
 * 不一致窗口（从「最长 30 分钟」到「百毫秒级」），TTL 兜的是"异常情况下"的底线，
 * 二者分工不同，互不替代。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopCacheService {

    private static final String SHOP_DETAIL_PREFIX = "cache:shop:detail:";

    /** 缓存一致性的最终安全网，详见类注释 */
    private static final Duration SHOP_DETAIL_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public Shop getShopDetail(Long shopId) {
        String raw = stringRedisTemplate.opsForValue().get(cacheKey(shopId));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Shop.class);
        } catch (JsonProcessingException e) {
            log.warn("[ShopCache] decode failed, evict cache: shopId={}", shopId, e);
            deleteShopDetail(shopId);
            return null;
        }
    }

    public void putShopDetail(Shop shop) {
        if (shop == null || shop.getId() == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    cacheKey(shop.getId()),
                    objectMapper.writeValueAsString(shop),
                    SHOP_DETAIL_TTL);
        } catch (JsonProcessingException e) {
            log.warn("[ShopCache] encode failed: shopId={}", shop.getId(), e);
        }
    }

    public void deleteShopDetail(Long shopId) {
        if (shopId != null) {
            stringRedisTemplate.delete(cacheKey(shopId));
        }
    }

    private String cacheKey(Long shopId) {
        return SHOP_DETAIL_PREFIX + shopId;
    }
}
