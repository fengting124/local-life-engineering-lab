package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.FollowRelation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户关注关系 Mapper，继承 {@link BaseMapper} 获得通用 CRUD 能力。
 *
 * <h2>当前阶段所用方法</h2>
 * <ul>
 *   <li>{@code insert(FollowRelation)} — 关注（新增一条关注记录）</li>
 *   <li>{@code delete(Wrapper)} — 取关（物理删除，按 follower_user_id + followed_user_id 精确删除）</li>
 *   <li>{@code selectOne(Wrapper)} — 查「我是否关注了某人」（校验重复关注 / 取关）</li>
 *   <li>{@code selectList(Wrapper)} — 查我的关注列表 / 粉丝列表</li>
 * </ul>
 *
 * <h2>注意</h2>
 * <p>FollowRelation 没有 deleted 字段，不使用逻辑删除。
 * {@code BaseMapper.deleteById} / {@code delete(Wrapper)} 均执行物理 DELETE SQL。
 */
@Mapper
public interface FollowRelationMapper extends BaseMapper<FollowRelation> {
}
