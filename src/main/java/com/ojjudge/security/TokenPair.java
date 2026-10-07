package com.ojjudge.security;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一次签发的令牌对，仅在 Service 与 Controller 之间传递。
 * refreshToken 由 Controller 写入 HttpOnly Cookie，绝不下发到响应体。
 */
@Data
@AllArgsConstructor
public class TokenPair {

    private String accessToken;

    private String refreshToken;

    /**
     * accessToken 有效期（秒）
     */
    private long expiresInSeconds;
}
