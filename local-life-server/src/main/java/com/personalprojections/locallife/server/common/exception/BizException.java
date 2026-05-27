package com.personalprojections.locallife.server.common.exception;

import com.personalprojections.locallife.server.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常，用于表达「可预期的业务规则拒绝」。
 *
 * <h2>与普通 RuntimeException 的区别</h2>
 * <ul>
 *   <li>BizException：业务规则导致的拒绝，例如「券已抢完」「订单状态不对」。
 *       这类异常是正常的业务流程，不需要打印堆栈，只记录 WARN 日志。</li>
 *   <li>RuntimeException / Exception：系统级错误，例如数据库连接失败、NullPointerException。
 *       这类异常是不预期的，需要打印完整堆栈，记录 ERROR 日志。</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   // Service 层：直接抛，不用关心如何构建响应
 *   if (stock <= 0) {
 *       throw new BizException(ErrorCode.COUPON_STOCK_EXHAUSTED);
 *   }
 *
 *   // 全局异常处理器（GlobalExceptionHandler）会捕获，自动构建 Result 响应
 * }</pre>
 *
 * <h2>为什么不用 checked exception（继承 Exception）</h2>
 * <p>业务异常在 Spring MVC 项目中统一由全局异常处理器处理，
 * 使用 checked exception 会强迫每一层 Service 都 try-catch 或 throws，
 * 增加样板代码，降低可读性。RuntimeException 可以穿透调用栈被顶层统一捕获。
 */
@Getter
public class BizException extends RuntimeException {

    /**
     * 对应的错误码枚举，包含 HTTP 状态码、code 字符串和 message。
     * 全局异常处理器通过此字段构建响应。
     */
    private final ErrorCode errorCode;

    /**
     * 抛出业务异常（使用 ErrorCode 预定义的 message）。
     *
     * <p>这是最常用的构造方式：
     * <pre>{@code
     *   throw new BizException(ErrorCode.ORDER_NOT_FOUND);
     * }</pre>
     *
     * @param errorCode 业务错误码
     */
    public BizException(ErrorCode errorCode) {
        // 把 message 传给父类，方便日志打印时通过 e.getMessage() 看到描述
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 抛出业务异常（自定义动态 message，覆盖枚举中的默认描述）。
     *
     * <p>当错误描述需要包含运行时信息时使用：
     * <pre>{@code
     *   throw new BizException(ErrorCode.SYS_PARAM_INVALID,
     *       "参数 [mobile] 格式不正确，当前值：" + mobile);
     * }</pre>
     *
     * @param errorCode 业务错误码（提供 HTTP 状态码和 code）
     * @param message   运行时动态拼装的错误描述
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
