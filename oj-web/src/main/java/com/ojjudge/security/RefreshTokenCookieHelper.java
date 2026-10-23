package com.ojjudge.security;

import com.ojjudge.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Refresh Token 的 Cookie 读写。
 *
 * <p>HttpOnly + SameSite=Lax：前端 JS 读不到，同时挡住大部分 CSRF 场景。
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookieHelper {

    private final JwtProperties jwtProperties;

    public void write(HttpServletResponse response, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(refreshToken, jwtProperties.getRefreshExpirationSeconds()).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", 0).toString());
    }

    /**
     * 从请求中读取 refresh token，不存在返回 null
     */
    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (jwtProperties.getRefreshCookieName().equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie build(String value, long maxAgeSeconds) {
        return ResponseCookie.from(jwtProperties.getRefreshCookieName(), value)
                .httpOnly(true)
                .secure(jwtProperties.isRefreshCookieSecure())
                .sameSite("Lax")
                .path(jwtProperties.getRefreshCookiePath())
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }
}
