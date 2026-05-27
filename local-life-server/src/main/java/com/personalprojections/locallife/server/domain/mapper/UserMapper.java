package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper，继承 MyBatis-Plus 的 BaseMapper，获得基础 CRUD 能力。
 *
 * <h2>BaseMapper 提供了哪些方法</h2>
 * <p>继承 {@code BaseMapper<User>} 后，无需编写任何 SQL，直接就有：
 * <ul>
 *   <li>{@code insert(User user)}                   - 插入一条记录</li>
 *   <li>{@code deleteById(Long id)}                 - 按主键逻辑删除（deleted=1）</li>
 *   <li>{@code updateById(User user)}               - 按主键更新（只更新非 null 字段）</li>
 *   <li>{@code selectById(Long id)}                 - 按主键查询</li>
 *   <li>{@code selectOne(QueryWrapper wrapper)}     - 按条件查询单条</li>
 *   <li>{@code selectList(QueryWrapper wrapper)}    - 按条件查询多条</li>
 *   <li>{@code selectPage(IPage page, Wrapper w)}   - 分页查询</li>
 * </ul>
 * 所有查询都自动追加 {@code WHERE deleted = 0}（因为 User 实体有 @TableLogic 注解）。
 *
 * <h2>什么时候需要自定义 SQL</h2>
 * <p>复杂的多表关联查询、需要 JOIN 的场景，才需要在对应的 XML 文件中写 SQL。
 * 单表 CRUD 全部使用 BaseMapper 提供的方法即可。
 *
 * <h2>为什么用 @Mapper 而不是 @MapperScan</h2>
 * <p>两种方式都可以。本项目选择在每个 Mapper 接口上加 {@code @Mapper}，
 * 好处是 IDE（IntelliJ IDEA）能识别，跳转更方便；
 * 也可以在 Application 类上加 {@code @MapperScan("...mapper")} 统一扫描。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 当前 user 表的查询全部通过 BaseMapper + QueryWrapper 完成，无需自定义 SQL
    // 如果后续需要复杂查询（如 JOIN merchant 表），在此处声明方法，
    // 对应的 SQL 写在 src/main/resources/mapper/UserMapper.xml 中
}
