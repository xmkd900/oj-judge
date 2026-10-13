package com.ojjudge.exception;

import com.ojjudge.common.BaseResponse;
import com.ojjudge.common.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 这几个业务码代表"认证态有问题"，统一用 HTTP 401 回给前端，
     * 好让 axios 拦截器像处理 Spring Security 的 401 一样处理它们
     */
    private static final int[] AUTH_ERROR_CODES = {
            ErrorCode.NOT_LOGIN_ERROR.getCode(),
            ErrorCode.TOKEN_EXPIRED.getCode(),
            ErrorCode.TOKEN_INVALID.getCode(),
            ErrorCode.USER_LOGIN_FAILED.getCode()
    };

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e, HttpServletResponse response) {
        log.error("BusinessException: {}", e.getMessage());
        for (int code : AUTH_ERROR_CODES) {
            if (code == e.getCode()) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                break;
            }
        }
        return new BaseResponse<>(e.getCode(), e.getDescription(), null);
    }

    /**
     * 方法级权限校验失败（@PreAuthorize 抛出）。
     *
     * <p>必须单独处理：AccessDeniedException 是 RuntimeException，
     * 不拦的话会掉进下面的兜底分支变成"系统内部异常 50000"，
     * 客户端就会以为服务端炸了，而实际上只是没权限。
     *
     * <p>注意它是在 Controller 方法内部抛出的，Spring Security 的
     * ExceptionTranslationFilter / AccessDeniedHandler 管不到（它们在 DispatcherServlet 外层）。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public BaseResponse<?> accessDeniedHandler(AccessDeniedException e, HttpServletResponse response) {
        log.warn("接口权限不足：{}", e.getMessage());
        // 与 AccessDeniedHandlerImpl 保持一致：HTTP 403 + code 40101
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return new BaseResponse<>(ErrorCode.NO_AUTH_ERROR.getCode(), "无权限访问该接口", null);
    }

    /**
     * Bean Validation 失败（@Valid）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> validExceptionHandler(MethodArgumentNotValidException e) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        String message = fieldErrors.isEmpty()
                ? ErrorCode.PARAMS_ERROR.getMessage()
                : fieldErrors.get(0).getField() + "：" + fieldErrors.get(0).getDefaultMessage();
        log.error("参数校验失败：{}", message);
        return new BaseResponse<>(ErrorCode.PARAMS_ERROR.getCode(), message, null);
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException: ", e);
        return new BaseResponse<>(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage(), null);
    }
}
