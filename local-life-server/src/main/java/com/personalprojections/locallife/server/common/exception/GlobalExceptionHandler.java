package com.personalprojections.locallife.server.common.exception;

import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>集中处理所有从 Controller 层向上抛出的异常，统一构建 {@link Result} 响应，
 * 设置对应的 HTTP 状态码，并按异常类型决定日志级别。
 *
 * <h2>处理策略</h2>
 * <ul>
 *   <li>{@link BizException}          → 业务规则拒绝，WARN 日志（不打堆栈），返回对应业务码</li>
 *   <li>{@link MethodArgumentNotValidException} → 参数校验失败，WARN 日志，返回 SYS_PARAM_INVALID</li>
 *   <li>其他 {@link Exception}        → 系统级错误，ERROR 日志（打完整堆栈），返回 SYS_BUSY</li>
 * </ul>
 *
 * <h2>为什么异常处理要集中在这里</h2>
 * <p>如果每个 Controller 方法都 try-catch，会产生大量重复代码，
 * 而且错误处理逻辑散落各处，难以维护。
 * {@code @RestControllerAdvice} 是 AOP 的一种应用，
 * 拦截所有 {@code @RestController} 抛出的异常，统一处理。
 *
 * <h2>HTTP 状态码设置</h2>
 * <p>Spring MVC 默认把所有 {@code @ExceptionHandler} 响应的 HTTP 状态码设为 200。
 * 本项目要求 HTTP 状态码与业务错误码对应（400/401/403/429/500），
 * 所以需要手动通过 {@link HttpServletResponse#setStatus} 设置。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常（BizException）。
     *
     * <p>BizException 是可预期的业务规则拒绝，例如：
     * <ul>
     *   <li>验证码错误</li>
     *   <li>库存不足</li>
     *   <li>订单状态不合法</li>
     * </ul>
     *
     * <p>这类异常不需要打印堆栈（正常业务流程），只记录 WARN 级别日志即可。
     *
     * @param e        业务异常，包含 errorCode
     * @param response HTTP 响应，用于设置状态码
     * @return 统一响应体
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e, HttpServletResponse response) {
        ErrorCode errorCode = e.getErrorCode();
        // WARN 级别：业务拒绝是预期行为，不用告警，但需要记录供统计分析
        log.warn("业务异常 [{}] - {}", errorCode.getCode(), e.getMessage());
        // 设置 HTTP 状态码（400/401/403/429 等）
        response.setStatus(errorCode.getHttpStatus());
        return Result.fail(errorCode, e.getMessage());
    }

    /**
     * 处理参数校验失败异常（@Valid / @Validated 触发）。
     *
     * <p>当 Controller 方法参数标注了 {@code @Valid}，Spring 会在调用方法前执行校验。
     * 校验失败时抛出 {@link MethodArgumentNotValidException}，本方法统一处理。
     *
     * <p>响应的 message 会拼装所有校验失败的字段，例如：
     * <pre>
     *   "mobile: 手机号格式不正确; password: 密码不能为空"
     * </pre>
     *
     * @param e        参数校验失败异常，包含所有字段的校验错误
     * @param response HTTP 响应，用于设置状态码
     * @return 统一响应体，code = SYS_PARAM_INVALID
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e,
                                                   HttpServletResponse response) {
        // 把所有字段的校验错误拼成一条描述，例如 "mobile: 手机号格式不正确; nickname: 昵称不能为空"
        String message = e.getBindingResult().getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        log.warn("参数校验失败 - {}", message);
        response.setStatus(ErrorCode.SYS_PARAM_INVALID.getHttpStatus());
        return Result.fail(ErrorCode.SYS_PARAM_INVALID, message);
    }

    /**
     * 兜底处理：捕获所有未被上面方法处理的异常（系统级错误）。
     *
     * <p>这类异常是不预期的，例如：
     * <ul>
     *   <li>数据库连接失败（DataAccessException）</li>
     *   <li>Redis 不可用（RedisConnectionException）</li>
     *   <li>空指针异常（NullPointerException，说明有 bug）</li>
     * </ul>
     *
     * <p>ERROR 级别打完整堆栈，方便排查。
     * 响应返回 SYS_BUSY，不暴露技术细节给前端（防止信息泄露）。
     *
     * @param e        未预期的系统级异常
     * @param response HTTP 响应，用于设置状态码
     * @return 统一响应体，code = SYS_BUSY
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletResponse response) {
        // ERROR 级别，打完整堆栈，这是真正需要排查的问题
        log.error("系统异常", e);
        response.setStatus(ErrorCode.SYS_BUSY.getHttpStatus());
        return Result.fail(ErrorCode.SYS_BUSY);
    }
}
