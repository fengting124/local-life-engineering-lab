package com.personalprojections.locallife.copilot.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具审计日志 Mapper。
 * 只需 insert，不需要自定义查询（分析用 SQL 工具直接查表）。
 */
@Mapper
public interface ToolAuditMapper extends BaseMapper<ToolAuditLog> {
}
