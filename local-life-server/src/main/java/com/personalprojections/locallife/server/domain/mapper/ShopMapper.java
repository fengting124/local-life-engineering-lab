package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.Shop;
import org.apache.ibatis.annotations.Mapper;

/**
 * 门店 Mapper，继承 BaseMapper 获得完整 CRUD 能力。
 *
 * <h2>常用查询示例（在 Service 层写）</h2>
 * <pre>{@code
 *   // 按商家 ID 查该商家的所有门店（商家管理后台）
 *   shopMapper.selectList(
 *       new LambdaQueryWrapper<Shop>()
 *           .eq(Shop::getMerchantId, merchantId)
 *           .orderByDesc(Shop::getCreatedAt)
 *   );
 *
 *   // 只查 ONLINE 状态的门店（用户端）
 *   shopMapper.selectList(
 *       new LambdaQueryWrapper<Shop>()
 *           .eq(Shop::getStatus, "ONLINE")
 *           .eq(Shop::getCategoryId, categoryId)
 *   );
 * }</pre>
 *
 * <h2>后续扩展</h2>
 * <p>Elasticsearch 接入后，C 端的门店搜索（关键词搜索、Geo 距离搜索）
 * 直接查 ES，MySQL 只用于精确查询（按 ID 查单条详情）。
 * 复杂的 JOIN 查询（如门店 + 最新笔记）届时在此声明接口，XML 中写 SQL。
 */
@Mapper
public interface ShopMapper extends BaseMapper<Shop> {
}
