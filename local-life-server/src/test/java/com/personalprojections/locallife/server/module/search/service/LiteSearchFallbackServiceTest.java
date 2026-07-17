package com.personalprojections.locallife.server.module.search.service;

import com.personalprojections.locallife.server.common.result.PageResult;
import com.personalprojections.locallife.server.domain.entity.Post;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.module.search.dto.PostSearchRequest;
import com.personalprojections.locallife.server.module.search.dto.PostSearchVO;
import com.personalprojections.locallife.server.module.search.dto.ShopSearchRequest;
import com.personalprojections.locallife.server.module.search.dto.ShopSearchVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiteSearchFallbackServiceTest {

    private final LiteShopSearchService shopSearchService = new LiteShopSearchService();
    private final LitePostSearchService postSearchService = new LitePostSearchService();

    @Test
    void shopSearchReturnsEmptyPageWhenElasticsearchIsDisabled() {
        ShopSearchRequest request = new ShopSearchRequest();
        request.setPageNumber(2);
        request.setPageSize(5);

        PageResult<ShopSearchVO> result = shopSearchService.searchShops(request);

        assertThat(result.getTotal()).isZero();
        assertThat(result.getPageNumber()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(5);
        assertThat(result.getItems()).isEmpty();
    }

    @Test
    void postSearchReturnsEmptyPageWhenElasticsearchIsDisabled() {
        PostSearchRequest request = new PostSearchRequest();
        request.setPageNumber(3);
        request.setPageSize(10);

        PageResult<PostSearchVO> result = postSearchService.searchPosts(request);

        assertThat(result.getTotal()).isZero();
        assertThat(result.getPageNumber()).isEqualTo(3);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getItems()).isEmpty();
    }

    @Test
    void indexSyncOperationsAreNoopsWhenElasticsearchIsDisabled() {
        shopSearchService.syncShop(Shop.builder().id(1L).shopName("lite shop").build());
        shopSearchService.removeShop(1L);

        postSearchService.syncPost(Post.builder().id(2L).title("lite post").build(), "lite shop");
        postSearchService.removePost(2L);
        postSearchService.updateLikeCount(2L, 10);
    }
}
