package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.SideEffectLedger;
import org.apache.ibatis.annotations.Mapper;

/**
 * 高风险副作用账本 Mapper。
 */
@Mapper
public interface SideEffectLedgerMapper extends BaseMapper<SideEffectLedger> {
}
