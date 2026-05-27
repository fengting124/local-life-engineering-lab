package com.personalprojections.locallife.server.common.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录用户摘要 DTO，存储在 Redis 和 ThreadLocal 中。
 *
 * <h2>为什么不直接存完整的 User 实体</h2>
 * <p>每次请求鉴权时都要从 Redis 反序列化用户数据。
 * 完整 User 实体包含所有字段（头像 URL、个人简介、注册时间等），
 * 但鉴权只需要 userId 和 status，存完整对象会：
 * <ol>
 *   <li>增大 Redis 存储空间（每个在线用户都占用）</li>
 *   <li>增大 Redis 读取流量（每次请求都要反序列化更多字节）</li>
 *   <li>引入耦合：User 实体加字段时会影响 Redis 中已有的序列化数据</li>
 * </ol>
 *
 * <h2>字段选取原则</h2>
 * <p>只存「每次请求都可能用到」的高频字段：
 * <ul>
 *   <li>{@code userId}   - 几乎所有业务操作都需要</li>
 *   <li>{@code mobile}   - 脱敏后展示，或记录操作日志用</li>
 *   <li>{@code nickname} - 部分接口直接返回（如当前用户信息），避免再查 DB</li>
 *   <li>{@code status}   - 鉴权拦截器需要检查账号是否被禁用</li>
 * </ul>
 *
 * <h2>Redis Key 对应关系</h2>
 * <pre>
 *   Key:   login:token:{token}
 *   Value: JSON 序列化的 LoginUserDTO
 *   TTL:   7 天（每次请求可以选择是否刷新 TTL）
 * </pre>
 *
 * <h2>序列化说明</h2>
 * <p>使用 Jackson 序列化为 JSON 存入 Redis（不使用 JDK 序列化）。
 * 原因：JSON 可读、与语言无关、字段增删兼容性更好。
 * Jackson 的 {@code @NoArgsConstructor} 是反序列化必须的（Jackson 需要无参构造）。
 */
@Data
@Builder
@NoArgsConstructor   // Jackson 反序列化需要无参构造
@AllArgsConstructor  // @Builder 需要全参构造
public class LoginUserDTO {

    /**
     * 用户 ID，雪花算法生成的 Long。
     * 注意：存 Redis 时序列化为 JSON，Long 的精度不会丢失（JSON 数字类型可以精确表示）。
     * 响应给前端时需要转为字符串（在 VO 层处理），因为 JS Number 最大安全整数约 9 千万亿，
     * 而雪花 ID 可达 2^63，传输时会被截断。
     */
    private Long userId;

    /**
     * 手机号（脱敏存储：138****8000）。
     * 注意：存入 Redis 的是脱敏后的手机号，不存完整手机号。
     * 原因：Token 存储在 Redis 中，万一 Redis 被攻击，减少敏感信息泄露。
     * 完整手机号只存在 MySQL 的 user 表中。
     */
    private String mobile;

    /**
     * 用户昵称，部分接口直接用此字段，避免多一次 DB 查询。
     * 注意：如果用户修改了昵称，需要同步更新 Redis 中的值（或等 TTL 过期后自然刷新）。
     */
    private String nickname;

    /**
     * 账号状态：ENABLED（正常）/ DISABLED（被禁用）。
     * 鉴权拦截器从 Redis 读取摘要后，会检查此状态，DISABLED 时返回 403。
     * 这样管理员禁用账号后，不需要等 Token TTL 过期，下次请求就会被拦截。
     */
    private String status;
}
