package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.Comment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 笔记评论 Mapper，继承 {@link BaseMapper} 获得通用 CRUD 能力。
 *
 * <h2>当前阶段所用方法</h2>
 * <ul>
 *   <li>{@code insert(Comment)} — 发表评论</li>
 *   <li>{@code selectList(Wrapper)} — 按 post_id 查评论列表（按 created_at 升序）</li>
 *   <li>{@code deleteById(Long)} — 逻辑删除评论</li>
 * </ul>
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
