package com.personalprojections.locallife.server.module.search.service;

import com.personalprojections.locallife.server.common.result.PageResult;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.module.search.dto.ShopSearchRequest;
import com.personalprojections.locallife.server.module.search.dto.ShopSearchVO;

public interface ShopSearchOperations {

    void syncShop(Shop shop);

    void removeShop(Long shopId);

    PageResult<ShopSearchVO> searchShops(ShopSearchRequest req);
}
