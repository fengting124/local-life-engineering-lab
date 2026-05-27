package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商家 Mapper，继承 BaseMapper 获得完整 CRUD 能力。
 *
 * <p>当前所有商家查询均通过 BaseMapper + LambdaQueryWrapper 完成，
 * 无需自定义 SQL。
 *
 * <h2>常用查询示例（在 Service 层写）</h2>
 * <pre>{@code
 *   // 按用户 ID 查商家（判断当前用户是否是商家）
 *   merchantMapper.selectOne(
 *       new LambdaQueryWrapper<Merchant>()
 *           .eq(Merchant::getUserId, userId)
 *   );
 *
 *   // 按状态查商家列表（运营后台审核）
 *   merchantMapper.selectList(
 *       new LambdaQueryWrapper<Merchant>()
 *           .eq(Merchant::getStatus, "PENDING")
 *           .orderByAsc(Merchant::getCreatedAt)
 *   );
 * }</pre>
 */
@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {
}
