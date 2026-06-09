package com.personalprojections.locallife.server.module.shop;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.Merchant;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.module.merchant.service.MerchantService;
import com.personalprojections.locallife.server.module.shop.dto.CreateShopRequest;
import com.personalprojections.locallife.server.module.shop.dto.ShopVO;
import com.personalprojections.locallife.server.module.shop.dto.UpdateShopRequest;
import com.personalprojections.locallife.server.common.bloom.BloomFilterService;
import com.personalprojections.locallife.server.module.shop.service.ShopCacheService;
import com.personalprojections.locallife.server.module.search.service.ShopSearchService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * {@link com.personalprojections.locallife.server.module.shop.service.ShopService} 单元测试。
 *
 * <h2>测试重点</h2>
 * <ul>
 *   <li><b>getShopDetail 三级查询链路</b>：布隆过滤器 → Redis 缓存 → MySQL 的每条分支，
 *       尤其是「缓存命中但门店已下线」「布隆拦截不查 DB」这些容易写错的防御逻辑。</li>
 *   <li><b>写操作的归属 + 状态机校验</b>：updateShop / onlineShop / offlineShop
 *       的「非本人门店 → 403」「状态非法 → 400」。</li>
 *   <li><b>副作用编排</b>：createShop 必须把新 shopId 注册进布隆过滤器并双写 ES。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShopService 单元测试")
class ShopServiceTest {

    @Mock ShopMapper shopMapper;
    @Mock MerchantService merchantService;
    @Mock ShopSearchService shopSearchService;
    @Mock BloomFilterService bloomFilterService;
    @Mock ShopCacheService shopCacheService;

    @InjectMocks com.personalprojections.locallife.server.module.shop.service.ShopService shopService;

    private static final long SHOP_ID = 5001L;
    private static final long MERCHANT_ID = 100L;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // searchShops / listMyShops 用 LambdaQueryWrapper，构建时需 Shop 的 TableInfo
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Shop.class);
    }

    private Shop shop(String status, long merchantId) {
        return Shop.builder()
                .id(SHOP_ID).merchantId(merchantId).shopName("测试火锅店")
                .categoryId(1).coverImage("").description("好吃").address("西湖区")
                .longitude(new BigDecimal("120.15")).latitude(new BigDecimal("30.27"))
                .phone("").businessHours("10-22").score(BigDecimal.ZERO).status(status)
                .build();
    }

    // =========================================================
    // getShopDetail：布隆 → 缓存 → DB
    // =========================================================

    @Test
    @DisplayName("布隆过滤器判定不存在 → 直接 NOT_FOUND，绝不查缓存/DB")
    void getShopDetail_bloomMiss_throwsNotFound_andSkipsCacheAndDb() {
        when(bloomFilterService.mightContainShopId(SHOP_ID)).thenReturn(false);

        assertThatThrownBy(() -> shopService.getShopDetail(SHOP_ID))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHOP_NOT_FOUND);

        // 防穿透的核心价值：被布隆拦下后，缓存和 DB 一次都不能碰
        verifyNoInteractions(shopCacheService);
        verify(shopMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("缓存命中且 ONLINE → 直接返回，不查 DB")
    void getShopDetail_cacheHitOnline_returnsVo_withoutDb() {
        when(bloomFilterService.mightContainShopId(SHOP_ID)).thenReturn(true);
        when(shopCacheService.getShopDetail(SHOP_ID)).thenReturn(shop("ONLINE", MERCHANT_ID));

        ShopVO vo = shopService.getShopDetail(SHOP_ID);

        assertThat(vo.getShopId()).isEqualTo(String.valueOf(SHOP_ID));
        assertThat(vo.getStatus()).isEqualTo("ONLINE");
        verify(shopMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("缓存命中但门店已下线 → NOT_FOUND（缓存可能滞后于下线）")
    void getShopDetail_cacheHitOffline_throwsNotFound() {
        when(bloomFilterService.mightContainShopId(SHOP_ID)).thenReturn(true);
        when(shopCacheService.getShopDetail(SHOP_ID)).thenReturn(shop("OFFLINE", MERCHANT_ID));

        assertThatThrownBy(() -> shopService.getShopDetail(SHOP_ID))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHOP_NOT_FOUND);
    }

    @Test
    @DisplayName("缓存未命中 + DB 命中 ONLINE → 回填缓存并返回")
    void getShopDetail_cacheMiss_dbOnline_putsCacheAndReturns() {
        when(bloomFilterService.mightContainShopId(SHOP_ID)).thenReturn(true);
        when(shopCacheService.getShopDetail(SHOP_ID)).thenReturn(null);
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE", MERCHANT_ID));

        ShopVO vo = shopService.getShopDetail(SHOP_ID);

        assertThat(vo.getShopId()).isEqualTo(String.valueOf(SHOP_ID));
        verify(shopCacheService).putShopDetail(any(Shop.class));  // 回填缓存
    }

    @Test
    @DisplayName("缓存未命中 + DB 不存在 → NOT_FOUND，不回填缓存")
    void getShopDetail_cacheMiss_dbNull_throwsNotFound() {
        when(bloomFilterService.mightContainShopId(SHOP_ID)).thenReturn(true);
        when(shopCacheService.getShopDetail(SHOP_ID)).thenReturn(null);
        when(shopMapper.selectById(SHOP_ID)).thenReturn(null);

        assertThatThrownBy(() -> shopService.getShopDetail(SHOP_ID))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHOP_NOT_FOUND);
        verify(shopCacheService, never()).putShopDetail(any());
    }

    @Test
    @DisplayName("缓存未命中 + DB 命中但 OFFLINE → NOT_FOUND，不回填缓存")
    void getShopDetail_cacheMiss_dbOffline_throwsNotFound() {
        when(bloomFilterService.mightContainShopId(SHOP_ID)).thenReturn(true);
        when(shopCacheService.getShopDetail(SHOP_ID)).thenReturn(null);
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("OFFLINE", MERCHANT_ID));

        assertThatThrownBy(() -> shopService.getShopDetail(SHOP_ID))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHOP_NOT_FOUND);
        verify(shopCacheService, never()).putShopDetail(any());
    }

    // =========================================================
    // searchShops / listMyShops
    // =========================================================

    @Test
    @DisplayName("searchShops 把 DB 结果逐个映射为 VO")
    void searchShops_mapsAllToVo() {
        when(shopMapper.selectList(any())).thenReturn(List.of(shop("ONLINE", MERCHANT_ID), shop("ONLINE", 200L)));

        List<ShopVO> result = shopService.searchShops(1);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(vo -> assertThat(vo.getStatus()).isEqualTo("ONLINE"));
    }

    @Test
    @DisplayName("listMyShops 返回当前商家自己的门店")
    void listMyShops_returnsOwnShops() {
        when(merchantService.requireApprovedMerchant()).thenReturn(Merchant.builder().id(MERCHANT_ID).build());
        when(shopMapper.selectList(any())).thenReturn(List.of(shop("ONLINE", MERCHANT_ID)));

        List<ShopVO> result = shopService.listMyShops();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMerchantId()).isEqualTo(String.valueOf(MERCHANT_ID));
    }

    // =========================================================
    // createShop：副作用编排
    // =========================================================

    @Test
    @DisplayName("createShop 成功：DRAFT 状态 + 注册布隆 + 双写 ES")
    void createShop_success_registersBloomAndSyncsEs() {
        when(merchantService.requireApprovedMerchant()).thenReturn(Merchant.builder().id(MERCHANT_ID).build());
        doAnswer(inv -> { ((Shop) inv.getArgument(0)).setId(SHOP_ID); return 1; })
                .when(shopMapper).insert(any(Shop.class));

        CreateShopRequest req = new CreateShopRequest();
        req.setShopName("新店");
        req.setCategoryId(2);
        req.setAddress("滨江区");
        req.setLongitude(new BigDecimal("120.2"));
        req.setLatitude(new BigDecimal("30.2"));

        ShopVO vo = shopService.createShop(req);

        assertThat(vo.getStatus()).isEqualTo("DRAFT");
        verify(bloomFilterService).addShopId(SHOP_ID);     // 新 ID 实时注册布隆
        verify(shopSearchService).syncShop(any(Shop.class)); // 双写 ES
    }

    // =========================================================
    // 写操作的归属 + 状态机校验
    // =========================================================

    @Test
    @DisplayName("updateShop 操作他人门店 → 403 FORBIDDEN")
    void updateShop_notOwner_throwsForbidden() {
        when(merchantService.requireApprovedMerchant()).thenReturn(Merchant.builder().id(MERCHANT_ID).build());
        // 门店属于另一个商家
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE", 999L));

        assertThatThrownBy(() -> shopService.updateShop(SHOP_ID, new UpdateShopRequest()))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHOP_FORBIDDEN);
    }

    @Test
    @DisplayName("updateShop 门店已 CLOSED → 状态非法 400")
    void updateShop_closed_throwsStatusIllegal() {
        when(merchantService.requireApprovedMerchant()).thenReturn(Merchant.builder().id(MERCHANT_ID).build());
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("CLOSED", MERCHANT_ID));

        assertThatThrownBy(() -> shopService.updateShop(SHOP_ID, new UpdateShopRequest()))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHOP_STATUS_ILLEGAL);
    }

    @Test
    @DisplayName("onlineShop：DRAFT → ONLINE，写前删缓存 + 同步 ES")
    void onlineShop_fromDraft_setsOnline() {
        when(merchantService.requireApprovedMerchant()).thenReturn(Merchant.builder().id(MERCHANT_ID).build());
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("DRAFT", MERCHANT_ID));

        ShopVO vo = shopService.onlineShop(SHOP_ID);

        assertThat(vo.getStatus()).isEqualTo("ONLINE");
        verify(shopCacheService).deleteShopDetail(SHOP_ID);
        verify(shopMapper).updateById(any(Shop.class));
        verify(shopSearchService).syncShop(any(Shop.class));
    }

    @Test
    @DisplayName("onlineShop：已是 ONLINE → 状态非法 400")
    void onlineShop_alreadyOnline_throwsStatusIllegal() {
        when(merchantService.requireApprovedMerchant()).thenReturn(Merchant.builder().id(MERCHANT_ID).build());
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE", MERCHANT_ID));

        assertThatThrownBy(() -> shopService.onlineShop(SHOP_ID))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHOP_STATUS_ILLEGAL);
    }

    @Test
    @DisplayName("offlineShop：ONLINE → OFFLINE")
    void offlineShop_fromOnline_setsOffline() {
        when(merchantService.requireApprovedMerchant()).thenReturn(Merchant.builder().id(MERCHANT_ID).build());
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE", MERCHANT_ID));

        ShopVO vo = shopService.offlineShop(SHOP_ID);

        assertThat(vo.getStatus()).isEqualTo("OFFLINE");
        verify(shopCacheService).deleteShopDetail(SHOP_ID);
    }

    @Test
    @DisplayName("offlineShop：非 ONLINE → 状态非法 400")
    void offlineShop_notOnline_throwsStatusIllegal() {
        when(merchantService.requireApprovedMerchant()).thenReturn(Merchant.builder().id(MERCHANT_ID).build());
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("DRAFT", MERCHANT_ID));

        assertThatThrownBy(() -> shopService.offlineShop(SHOP_ID))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHOP_STATUS_ILLEGAL);
    }
}
