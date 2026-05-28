package com.personalprojections.locallife.server.common.result;

import lombok.Getter;

/**
 * 业务错误码枚举，集中管理所有模块的错误码。
 *
 * <h2>命名规则</h2>
 * <pre>
 *   {模块前缀}_{错误描述}
 *   全大写，下划线分隔
 * </pre>
 *
 * <h2>模块前缀</h2>
 * <ul>
 *   <li>AUTH_    - 登录与鉴权</li>
 *   <li>USER_    - 用户</li>
 *   <li>SHOP_    - 门店</li>
 *   <li>POST_    - 内容笔记</li>
 *   <li>COUPON_  - 优惠券</li>
 *   <li>SECKILL_ - 秒杀</li>
 *   <li>ORDER_   - 订单</li>
 *   <li>PAYMENT_ - 支付</li>
 *   <li>SYS_     - 系统级（通用）</li>
 * </ul>
 *
 * <h2>对应 HTTP 状态码规则</h2>
 * <ul>
 *   <li>业务拒绝（参数错误、资源不存在、规则冲突）→ 400</li>
 *   <li>未登录 / Token 失效                        → 401</li>
 *   <li>有登录但无权限                             → 403</li>
 *   <li>限流                                       → 429</li>
 *   <li>系统内部错误                               → 500</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   // 业务代码：抛出异常即可，不需要手动构建响应
 *   throw new BizException(ErrorCode.COUPON_STOCK_EXHAUSTED);
 *
 *   // 全局异常处理器会捕获，调用 Result.fail(errorCode) 构建响应：
 *   // {"code":"COUPON_STOCK_EXHAUSTED","message":"优惠券已抢完","data":null,...}
 * }</pre>
 *
 * <h2>新增错误码规则</h2>
 * <ol>
 *   <li>在本枚举对应模块区域追加，保持分组整洁</li>
 *   <li>同步更新 docs/01-project/10-接口规范文档.md 的错误码清单</li>
 *   <li>禁止在业务代码中直接写字符串，必须引用此枚举</li>
 * </ol>
 */
@Getter
public enum ErrorCode {

    // ===================================================
    // SYS - 系统级（通用）
    // ===================================================

    /**
     * 通用参数校验失败。
     * 由 Spring Validation（@Valid）触发，全局异常处理器统一捕获 MethodArgumentNotValidException。
     * message 会被动态替换为具体的校验失败字段描述。
     */
    SYS_PARAM_INVALID(400, "SYS_PARAM_INVALID", "请求参数不合法"),

    /**
     * 系统繁忙，兜底错误码。
     * 当 Redis、MQ 等中间件不可用，或出现未预期的 RuntimeException 时返回此码。
     * 前端收到此码应提示「系统繁忙，请稍后重试」，不应展示技术细节。
     */
    SYS_BUSY(500, "SYS_BUSY", "系统繁忙，请稍后重试"),

    // ===================================================
    // AUTH - 登录与鉴权
    // ===================================================

    /**
     * 验证码错误或已过期。
     * 验证码存储在 Redis（Key: login:code:{mobile}），TTL 5 分钟，使用后立即删除（一次性）。
     * 以下情况均返回此码：
     *   1. 用户输入的验证码与 Redis 中不一致
     *   2. 验证码已超过 5 分钟过期
     *   3. 验证码已被使用过（Redis Key 已删除）
     */
    AUTH_CODE_INVALID(400, "AUTH_CODE_INVALID", "验证码错误或已过期"),

    /**
     * 验证码发送过于频繁，触发限流。
     * 限流规则：手机号维度 60 秒内最多 1 次，1 小时内最多 5 次。
     * Redis Key：login:sms:mobile:{mobile}（1小时TTL）
     */
    AUTH_CODE_SEND_TOO_FREQUENT(429, "AUTH_CODE_SEND_TOO_FREQUENT", "验证码发送过于频繁，请稍后再试"),

    /**
     * 请求未携带 Token。
     * 鉴权拦截器检测到 Authorization Header 缺失或格式不是 "Bearer {token}" 时返回。
     */
    AUTH_TOKEN_MISSING(401, "AUTH_TOKEN_MISSING", "请先登录"),

    /**
     * Token 已失效，需要重新登录。
     * 以下情况均返回此码：
     *   1. Token 在 Redis 中不存在（7天TTL已过期）
     *   2. 用户主动退出后 Token 被删除
     *   3. 账号异常被强制下线（管理员操作）
     */
    AUTH_TOKEN_EXPIRED(401, "AUTH_TOKEN_EXPIRED", "登录已过期，请重新登录"),

    // ===================================================
    // USER - 用户
    // ===================================================

    /**
     * 账号被禁用，无法访问任何需要登录的接口。
     * 当用户状态为 DISABLED 时，鉴权拦截器在读取用户摘要后检查此状态。
     */
    USER_ACCOUNT_DISABLED(403, "USER_ACCOUNT_DISABLED", "账号已被禁用，如有疑问请联系客服"),

    /**
     * 目标用户不存在。
     * 触发场景：关注一个不存在的用户 ID，或查看一个已注销用户的主页。
     * 注意：不暴露「账号被禁用」等细节，统一返回「用户不存在」。
     */
    USER_NOT_FOUND(400, "USER_NOT_FOUND", "用户不存在"),

    // ===================================================
    // MERCHANT - 商家
    // ===================================================

    /**
     * 当前用户没有商家身份（尚未申请或申请记录不存在）。
     * 触发场景：调用需要商家权限的接口（创建门店、发布活动）时，
     * Service 层发现当前用户在 merchant 表中没有记录。
     */
    MERCHANT_NOT_FOUND(400, "MERCHANT_NOT_FOUND", "您还不是商家，请先申请商家资质"),

    /**
     * 商家审核未通过或已被禁用，无法执行商家操作。
     * 触发场景：merchant.status 不是 APPROVED（可能是 PENDING/REJECTED/DISABLED）。
     * 只有 APPROVED 状态的商家才能创建门店、发布活动。
     */
    MERCHANT_NOT_APPROVED(403, "MERCHANT_NOT_APPROVED", "商家资质审核未通过，暂无权限"),

    // ===================================================
    // SHOP - 门店
    // ===================================================

    /**
     * 门店不存在。
     * 注意：本项目不使用 HTTP 404，资源不存在统一走 400 + 业务码。
     * 原因：让前端只处理 code 字段，无需再分支判断 HTTP 状态码。
     */
    SHOP_NOT_FOUND(400, "SHOP_NOT_FOUND", "门店不存在"),

    /**
     * 门店未上线，暂不支持当前操作（如发布活动、创建订单）。
     * 门店状态流转：DRAFT → ONLINE ↔ OFFLINE → CLOSED
     */
    SHOP_NOT_ONLINE(400, "SHOP_NOT_ONLINE", "门店当前未上线"),

    /**
     * 无权操作该门店。
     * 触发场景：商家 A 尝试修改商家 B 的门店（越权操作）。
     * Service 层校验 shop.merchantId != 当前登录商家的 merchantId 时抛此异常。
     * 注意：不区分「不存在」和「无权限」，统一返回此码，防止枚举攻击（信息泄露）。
     */
    SHOP_FORBIDDEN(403, "SHOP_FORBIDDEN", "无权操作该门店"),

    /**
     * 门店当前状态不允许执行此操作。
     * 例如：尝试将 CLOSED 状态的门店重新上线（CLOSED 是终态，不可逆）。
     */
    SHOP_STATUS_ILLEGAL(400, "SHOP_STATUS_ILLEGAL", "当前门店状态不支持此操作"),

    // ===================================================
    // POST - 内容笔记
    // ===================================================

    /**
     * 用户发笔记过于频繁，触发用户维度限流。
     * Redis Key：publish:limit:{userId}，TTL 1 分钟，超过阈值拒绝。
     */
    POST_PUBLISH_TOO_FREQUENT(429, "POST_PUBLISH_TOO_FREQUENT", "发布过于频繁，请稍后再试"),

    /**
     * 笔记不存在（已被删除、未发布，或 ID 不合法）。
     * C 端查询时统一返回此码，不区分「不存在」和「未发布」，防止信息泄露。
     */
    POST_NOT_FOUND(400, "POST_NOT_FOUND", "笔记不存在"),

    /**
     * 无权操作该笔记。
     * 触发场景：尝试删除他人笔记。
     * 与 POST_NOT_FOUND 合并处理：不区分「不存在」和「无权限」，防枚举攻击。
     */
    POST_FORBIDDEN(403, "POST_FORBIDDEN", "无权操作该笔记"),

    /**
     * 无权操作该评论。
     * 触发场景：尝试删除他人评论，或评论不属于指定笔记。
     */
    COMMENT_FORBIDDEN(403, "COMMENT_FORBIDDEN", "无权操作该评论"),

    // ===================================================
    // FOLLOW - 关注关系
    // ===================================================

    /**
     * 不能关注自己。
     * 触发场景：POST /api/v1/follows/{userId} 时 targetUserId == 当前用户自己的 userId。
     */
    FOLLOW_SELF_NOT_ALLOWED(400, "FOLLOW_SELF_NOT_ALLOWED", "不能关注自己"),

    // ===================================================
    // COUPON - 优惠券
    // ===================================================

    /**
     * 优惠券库存已耗尽，无法再领取。
     * Redis Lua 脚本在预扣库存时判断，stock <= 0 时返回此码。
     */
    COUPON_STOCK_EXHAUSTED(400, "COUPON_STOCK_EXHAUSTED", "优惠券已抢完"),

    /**
     * 用户已领取过该优惠券，不可重复领取（一人一单限制）。
     * 通过 Redis Set（seckill:user:{sessionId}:{couponTemplateId}）判断，
     * 同时数据库有唯一索引 uk_user_coupon 兜底。
     */
    COUPON_ALREADY_RECEIVED(400, "COUPON_ALREADY_RECEIVED", "您已领取过该优惠券"),

    // ===================================================
    // SECKILL - 秒杀
    // ===================================================

    /**
     * 秒杀场次尚未开始，当前时间在 beginTime 之前。
     */
    SECKILL_NOT_STARTED(400, "SECKILL_NOT_STARTED", "秒杀活动尚未开始"),

    /**
     * 秒杀场次已结束，当前时间在 endTime 之后。
     */
    SECKILL_EXPIRED(400, "SECKILL_EXPIRED", "秒杀活动已结束"),

    // ===================================================
    // ORDER - 订单
    // ===================================================

    /**
     * 订单不存在，或当前用户无权查看该订单。
     * 注意：不区分「不存在」和「无权限」，统一返回此码，防止信息泄露（枚举攻击）。
     */
    ORDER_NOT_FOUND(400, "ORDER_NOT_FOUND", "订单不存在"),

    /**
     * 当前订单状态不允许执行此操作。
     * 例如：尝试取消一笔已支付的订单，或尝试支付一笔已关闭的订单。
     * 状态机保护：UPDATE ... WHERE status = 'WAIT_PAY'，affected rows = 0 时抛此异常。
     */
    ORDER_STATUS_ILLEGAL(400, "ORDER_STATUS_ILLEGAL", "当前订单状态不支持此操作"),

    /**
     * 优惠券不可用（下单时校验）。
     * 触发场景（统一返回此码，不区分具体原因，防止信息泄露）：
     *   1. 券不存在或不属于当前用户
     *   2. 券状态不是 UNUSED（已使用、已过期）
     *   3. 券已超过有效期（expire_at &lt; NOW()）
     *   4. 券模板已停用（status = INACTIVE）
     *   5. 并发下另一请求抢先核销了这张券
     */
    ORDER_COUPON_INVALID(400, "ORDER_COUPON_INVALID", "优惠券不可用"),

    /**
     * 订单金额未达到优惠券使用门槛。
     * 例如：满 100 元可用的券，订单只有 50 元。
     * 单独列出此错误码，方便前端展示「订单金额不足 XX 元，无法使用此券」。
     */
    ORDER_COUPON_BELOW_MIN_AMOUNT(400, "ORDER_COUPON_BELOW_MIN_AMOUNT", "订单金额未达到优惠券使用门槛"),

    // ===================================================
    // PAYMENT - 支付
    // ===================================================

    /**
     * 支付回调验签失败。
     * 支付渠道（Mock/Alipay/WeChat）回调时携带签名，服务端重新计算后比对。
     * 验签失败说明请求可能被篡改，直接拒绝，不更新任何状态。
     */
    PAYMENT_VERIFY_FAILED(400, "PAYMENT_VERIFY_FAILED", "支付回调验签失败"),

    /**
     * 支付金额与订单金额不符。
     * 回调中携带的实付金额（paidAmount）与数据库中订单应付金额（orderAmount）比对，不一致时拒绝。
     * 这是防止支付金额被篡改的最后一道防线。
     */
    PAYMENT_AMOUNT_MISMATCH(400, "PAYMENT_AMOUNT_MISMATCH", "支付金额与订单不符");

    // ===================================================
    // 枚举字段
    // ===================================================

    /**
     * 对应的 HTTP 状态码，由全局异常处理器读取并设置到 HTTP 响应。
     * 这样业务代码只需要关心「抛哪个错误码」，不需要关心 HTTP 状态码。
     */
    private final int httpStatus;

    /**
     * 错误码字符串，序列化到响应体的 code 字段。
     * 与枚举名称相同，单独存一个字段是为了方便 {@link Result} 直接取用，
     * 而不需要调用 errorCode.name()（可读性更好）。
     */
    private final String code;

    /**
     * 面向用户的错误描述，序列化到响应体的 message 字段。
     * 可直接展示给前端用户，不应包含技术细节（堆栈、SQL 等）。
     */
    private final String message;

    ErrorCode(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
