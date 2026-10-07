package com.ojjudge.common;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>注意：必须提供 getter，否则 Jackson 无法序列化，接口会直接 500。
 */
@Data
public class BaseResponse <T> implements Serializable {
    private static final long serialVersionUID = 1L;
    private int code;
    private String message;
    private T data;

    public BaseResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public BaseResponse(int code, T data) {
        this.code = code;
        this.data = data;
        this.message="ok";
    }

    public BaseResponse(ErrorCode errorCode) {
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
        this.data=null;

    }

    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(0, data);
    }


}

