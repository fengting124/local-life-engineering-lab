package com.personalprojections.locallife.server.module.search.service;

import com.personalprojections.locallife.server.common.result.PageResult;
import com.personalprojections.locallife.server.domain.entity.Post;
import com.personalprojections.locallife.server.module.search.dto.PostSearchRequest;
import com.personalprojections.locallife.server.module.search.dto.PostSearchVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@Profile("lite")
public class LitePostSearchService implements PostSearchOperations {

    @Override
    public void syncPost(Post post, String shopName) {
        log.debug("[Lite] Elasticsearch disabled; skip post index sync, postId={}", post.getId());
    }

    @Override
    public void removePost(Long postId) {
        log.debug("[Lite] Elasticsearch disabled; skip post index delete, postId={}", postId);
    }

    @Override
    public void updateLikeCount(Long postId, int likeCount) {
        log.debug("[Lite] Elasticsearch disabled; skip post like count sync, postId={}", postId);
    }

    @Override
    public PageResult<PostSearchVO> searchPosts(PostSearchRequest req) {
        log.debug("[Lite] Elasticsearch disabled; return empty post search result");
        return PageResult.of(0, req.getPageNumber(), req.getPageSize(), List.of());
    }
}
