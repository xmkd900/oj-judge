package com.ojjudge.security;

import com.ojjudge.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析。
 *
 * <p>两类令牌共用同一密钥，靠 {@code type} 声明区分，避免 refresh token 被当成 access token 使用：
 * <ul>
 *   <li>{@code type=access}：短期（默认 15 分钟），放 Authorization 头，服务端无状态校验</li>
 *   <li>{@code type=refresh}：长期（默认 14 天），放 HttpOnly Cookie，服务端在 Redis 中有记录，可吊销</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    public static final String CLAIM_TYPE = "type";
    public static final String CLAIM_ACCOUNT = "account";
    public static final String CLAIM_ROLE = "role";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties jwtProperties;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 Access Token
     *
     * @param userId   用户 id，作为 subject
     * @param account  用户账号
     * @param role     用户角色
     * @param jti      令牌唯一 id，用于登出时加入黑名单
     * @return 紧凑格式的 JWT 字符串
     */
    public String generateAccessToken(Long userId, String account, String role, String jti) {
        return buildToken(userId, account, role, jti, TYPE_ACCESS, jwtProperties.getAccessExpiration());
    }

    /**
     * 签发 Refresh Token
     */
    public String generateRefreshToken(Long userId, String account, String role, String jti) {
        return buildToken(userId, account, role, jti, TYPE_REFRESH, jwtProperties.getRefreshExpiration());
    }

    public String newJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String buildToken(Long userId, String account, String role, String jti, String type, long expiration) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .claim(CLAIM_ACCOUNT, account)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(expire)
                .signWith(getKey())
                .compact();
    }

    /**
     * 解析令牌，失败时抛出 {@link JwtException}
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 宽松解析：签名/过期异常都返回 null，交给上层决定如何处理
     */
    public Claims parseQuietly(String token) {
        try {
            return parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败：{}", e.getMessage());
            return null;
        }
    }

    public Long getUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public long getAccessExpirationSeconds() {
        return jwtProperties.getAccessExpiration() / 1000;
    }

    public long getRefreshExpirationSeconds() {
        return jwtProperties.getRefreshExpiration() / 1000;
    }
}
