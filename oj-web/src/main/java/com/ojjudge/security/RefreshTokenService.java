package com.ojjudge.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * Refresh Token 的 Redis 存储。
 *
 * <p>键设计：
 * <pre>
 *   auth:refresh:{userId}:{jti}   -> "1"，TTL = refresh token 有效期，代表一个"已签发的刷新令牌"
 *   auth:at:blacklist:{jti}       -> "1"，TTL = 该 access token 剩余寿命，登出后立刻失效
 * </pre>
 *
 * <p>由此获得三个能力：
 * <ul>
 *   <li>轮换（rotation）：每次刷新都换 jti，旧 refresh token 立即作废</li>
 *   <li>吊销：登出删除对应键，该设备的刷新令牌即刻失效</li>
 *   <li>重放检测：签名合法但 Redis 中已无记录 == 令牌被用过或已被偷用，判定为泄露并踢掉该用户全部设备</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String REFRESH_KEY_PREFIX = "auth:refresh:";
    private static final String ACCESS_BLACKLIST_PREFIX = "auth:at:blacklist:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 登记一个新签发的 refresh token
     */
    public void store(Long userId, String jti, long ttlSeconds) {
        stringRedisTemplate.opsForValue().set(refreshKey(userId, jti), "1", Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 轮换：删除旧的、登记新的。
     *
     * @return 旧令牌是否仍然有效；返回 false 说明发生了重放（令牌已用过 / 已被吊销）
     */
    public boolean rotate(Long userId, String oldJti, String newJti, long ttlSeconds) {
        String oldKey = refreshKey(userId, oldJti);
        Boolean deleted = stringRedisTemplate.delete(oldKey);
        boolean existed = Boolean.TRUE.equals(deleted);
        store(userId, newJti, ttlSeconds);
        return existed;
    }

    /**
     * 吊销单个 refresh token（单设备登出）
     */
    public void revoke(Long userId, String jti) {
        stringRedisTemplate.delete(refreshKey(userId, jti));
    }

    /**
     * 吊销该用户的全部 refresh token（疑似泄露 / 改密 / 封禁时调用）
     */
    public void revokeAll(Long userId) {
        Set<String> keys = stringRedisTemplate.keys(REFRESH_KEY_PREFIX + userId + ":*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    /**
     * 把 access token 加入黑名单，使其立即失效（弥补 JWT 无状态的短板）
     */
    public void blacklistAccessToken(String jti, long remainingSeconds) {
        if (remainingSeconds <= 0) {
            return;
        }
        stringRedisTemplate.opsForValue()
                .set(blacklistKey(jti), "1", Duration.ofSeconds(remainingSeconds));
    }

    public boolean isAccessTokenBlacklisted(String jti) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(blacklistKey(jti)));
    }

    private String refreshKey(Long userId, String jti) {
        return REFRESH_KEY_PREFIX + userId + ":" + jti;
    }

    private String blacklistKey(String jti) {
        return ACCESS_BLACKLIST_PREFIX + jti;
    }
}
