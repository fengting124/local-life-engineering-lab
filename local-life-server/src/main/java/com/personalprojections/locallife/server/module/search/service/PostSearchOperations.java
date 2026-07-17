package com.personalprojections.locallife.server.module.search.service;

import com.personalprojections.locallife.server.common.result.PageResult;
import com.personalprojections.locallife.server.domain.entity.Post;
import com.personalprojections.locallife.server.module.search.dto.PostSearchRequest;
import com.personalprojections.locallife.server.module.search.dto.PostSearchVO;

public interface PostSearchOperations {

    void syncPost(Post post, String shopName);

    void removePost(Long postId);

    void updateLikeCount(Long postId, int likeCount);

    PageResult<PostSearchVO> searchPosts(PostSearchRequest req);
}
