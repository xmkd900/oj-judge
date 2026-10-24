package com.ojjudge.common;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAMS_ERROR(40000, "请求参数错误"),
    NOT_LOGIN_ERROR(40100, "未登录"),
    NO_AUTH_ERROR(40101, "无权限"),
    NOT_FOUND_ERROR(40400, "请求数据不存在"),
    FORBIDDEN_ERROR(40300, "禁止访问"),
    SYSTEM_ERROR(50000, "系统内部异常"),
    OPERATION_ERROR(50001, "操作失败"),
    API_REQUEST_ERROR(50010, "接口调用失败"),
    USER_LOGIN_FAILED(40102, "用户账号或密码错误"),
    USER_REGISTER_FAILED(40103, "用户注册失败，请检查账号是否已存在"),
    TOKEN_EXPIRED(40110, "登录已过期，请刷新令牌"),
    TOKEN_INVALID(40111, "令牌无效或已失效，请重新登录"),

    /**
     * 限流。用 429xx 开头，和 HTTP 429 Too Many Requests 对得上，
     * 方便以后接入网关时直接映射成状态码。
     */
    RATE_LIMIT_ERROR(42900, "请求过于频繁，请稍后再试");

    /**
     * 状态码
     */
    private final int code;

    /**
     * 信息
     */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

}
