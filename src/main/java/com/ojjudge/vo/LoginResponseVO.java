package com.ojjudge.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 登录成功响应。
 *
 * <p>Refresh Token 不在这里——它通过 HttpOnly Cookie 下发，前端 JS 读不到。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Access Token，前端需放在 Authorization: Bearer 头上
     */
    private String accessToken;

    /**
     * Access Token 剩余有效期（秒），前端据此提前刷新
     */
    private Long expiresIn;

    private UserLoginVO user;
}
