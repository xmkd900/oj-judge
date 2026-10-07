package com.ojjudge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 相关配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * HMAC 签名密钥，长度需 >= 32 字节
     */
    private String secret;

    /**
     * Access Token 有效期（毫秒）
     */
    private long accessExpiration = 900000L;

    /**
     * Refresh Token 有效期（毫秒）
     */
    private long refreshExpiration = 1209600000L;

    /**
     * Refresh Token 下发的 Cookie 名称
     */
    private String refreshCookieName = "refresh_token";

    /**
     * Refresh Token Cookie 的 Path
     */
    private String refreshCookiePath = "/api/user";

    /**
     * Cookie 是否仅 https 传输
     */
    private boolean refreshCookieSecure = false;

    public long getAccessExpirationSeconds() {
        return accessExpiration / 1000;
    }

    public long getRefreshExpirationSeconds() {
        return refreshExpiration / 1000;
    }
}
