package com.personalprojections.locallife.server.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 统一响应包装器，所有接口的返回值必须包裹在此对象中。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code code}      - 业务码字符串。成功固定为 "OK"，失败为业务错误码（如 "COUPON_STOCK_EXHAUSTED"）。
 *                           使用字符串而非数字的原因：字符串可读性强，on-call 排查时无需查手册。</li>
 *   <li>{@code message}   - 面向用户的可读描述，失败时可直接展示给前端用户。</li>
 *   <li>{@code data}      - 业务数据，泛型 T。失败时为 null，Jackson 配置了 non_null 不序列化。</li>
 *   <li>{@code timestamp} - 服务端响应时间，ISO 8601 含时区（+08:00）。</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 *   // Controller 中返回成功
 *   return Result.ok(userVO);
 *
 *   // Controller 中返回失败（一般由全局异常处理器调用，业务代码抛异常即可）
 *   return Result.fail(ErrorCode.COUPON_STOCK_EXHAUSTED);
 * }</pre>
 *
 * @param <T> 响应数据的类型
 */
@Getter
public class Result<T> {

    /** 业务码。成功为 "OK"，失败为对应的 {@link ErrorCode} 枚举名称。 */
    private final String code;

    /** 面向用户的描述信息，可直接展示。 */
    private final String message;

    /**
     * 业务数据。
     * {@code @JsonInclude(JsonInclude.Include.NON_NULL)} 确保 data 为 null 时不序列化该字段，
     * 减少响应体积，同时避免前端收到多余的 null 字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final T data;

    /** 服务端响应时间，ISO 8601 格式含东八区时区，例如 "2026-05-27T10:00:00+08:00"。 */
    private final String timestamp;

    /**
     * 私有构造器，所有实例通过静态工厂方法创建，保证 timestamp 一定被填充。
     */
    private Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        // 记录服务端生成响应的时刻，固定东八区
        this.timestamp = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();
    }

    // ===== 静态工厂方法 =====

    /**
     * 返回成功响应（有数据）。
     *
     * <p>示例：
     * <pre>{@code
     *   Result<UserVO> result = Result.ok(userVO);
     *   // {"code":"OK","message":"操作成功","data":{...},"timestamp":"..."}
     * }</pre>
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 包含数据的成功响应
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>("OK", "操作成功", data);
    }

    /**
     * 返回成功响应（无数据，通常用于删除、更新等无需返回业务对象的操作）。
     *
     * <p>示例：
     * <pre>{@code
     *   Result<Void> result = Result.ok();
     *   // {"code":"OK","message":"操作成功","timestamp":"..."}  // data 字段不出现（non_null）
     * }</pre>
     *
     * @return 无数据的成功响应
     */
    public static Result<Void> ok() {
        return new Result<>("OK", "操作成功", null);
    }

    /**
     * 返回失败响应（通过 ErrorCode 枚举）。
     *
     * <p>这是最常用的失败响应方式。业务代码只需要 {@code throw new BizException(ErrorCode.xxx)}，
     * 全局异常处理器会捕获后调用此方法构建响应，业务代码不需要直接调用。
     *
     * <p>示例：
     * <pre>{@code
     *   Result<Void> result = Result.fail(ErrorCode.COUPON_STOCK_EXHAUSTED);
     *   // {"code":"COUPON_STOCK_EXHAUSTED","message":"优惠券已抢完","data":null,"timestamp":"..."}
     * }</pre>
     *
     * @param errorCode 错误码枚举，包含 code 和 message
     * @return 失败响应
     */
    public static Result<Void> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 返回失败响应（自定义消息，用于动态拼装错误信息的场景）。
     *
     * <p>大多数场景应使用 {@link #fail(ErrorCode)}，只有当错误消息需要包含运行时动态信息时才使用此方法。
     * 例如："参数 [mobile] 格式不正确，当前值：[abc123]"。
     *
     * @param errorCode 错误码枚举（提供 code）
     * @param message   运行时动态拼装的错误描述
     * @return 失败响应
     */
    public static Result<Void> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }
}
