package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.Post;
import org.apache.ibatis.annotations.Mapper;

/**
 * 探店笔记 Mapper，继承 MyBatis-Plus {@link BaseMapper} 获得所有通用 CRUD 能力。
 *
 * <h2>当前阶段所用方法（均来自 BaseMapper）</h2>
 * <ul>
 *   <li>{@code insert(Post)} — 发布笔记</li>
 *   <li>{@code selectById(Long)} — 查笔记详情（自动过滤 deleted = 1）</li>
 *   <li>{@code selectList(Wrapper)} — 按用户/门店查笔记列表</li>
 *   <li>{@code deleteById(Long)} — 逻辑删除笔记（改写为 UPDATE SET deleted = 1）</li>
 *   <li>{@code updateById(Post)} — 更新笔记字段（如 like_count 快照同步）</li>
 * </ul>
 *
 * <h2>后续扩展（接入 Elasticsearch 后）</h2>
 * <p>全文检索查询将由 ES 客户端承接，PostMapper 主要负责：
 * <ol>
 *   <li>ID 回查（ES 返回 ID 列表 → 数据库批量回查详情）</li>
 *   <li>数据写入时的 MySQL 主库更新</li>
 * </ol>
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {
    // 当前阶段 BaseMapper 提供的通用方法已够用，暂不需要自定义 SQL。
    // 后续如有复杂联表查询（如笔记 + 作者信息 + 门店信息），在此添加 @Select 注解方法。
}
