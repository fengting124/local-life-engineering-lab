package com.personalprojections.locallife.server.module.search.service;

import com.personalprojections.locallife.server.common.result.PageResult;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.module.search.dto.ShopSearchRequest;
import com.personalprojections.locallife.server.module.search.dto.ShopSearchVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@Profile("lite")
public class LiteShopSearchService implements ShopSearchOperations {

    @Override
    public void syncShop(Shop shop) {
        log.debug("[Lite] Elasticsearch disabled; skip shop index sync, shopId={}", shop.getId());
    }

    @Override
    public void removeShop(Long shopId) {
        log.debug("[Lite] Elasticsearch disabled; skip shop index delete, shopId={}", shopId);
    }

    @Override
    public PageResult<ShopSearchVO> searchShops(ShopSearchRequest req) {
        log.debug("[Lite] Elasticsearch disabled; return empty shop search result");
        return PageResult.of(0, req.getPageNumber(), req.getPageSize(), List.of());
    }
}
