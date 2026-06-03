package com.personalprojections.locallife.server.module.shop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.domain.entity.Shop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopCacheService {

    private static final String SHOP_DETAIL_PREFIX = "cache:shop:detail:";
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

    @Async
    public void delayedDeleteShopDetail(Long shopId) {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        deleteShopDetail(shopId);
        log.debug("[ShopCache] delayed double delete done: shopId={}", shopId);
    }

    private String cacheKey(Long shopId) {
        return SHOP_DETAIL_PREFIX + shopId;
    }
}
