package com.ojjudge.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的滑动窗口限流。
 *
 * <h3>为什么用滑动窗口，不用固定窗口</h3>
 * <p>固定窗口（INCR + EXPIRE）有个经典缺陷：<b>临界突刺</b>。
 * 窗口是 60 秒的话，用户在第 59 秒打满、第 61 秒又打满，
 * 实际 2 秒内就放过了 2 倍的量。对防刷来说这口子是真能被人利用的。
 *
 * <h3>为什么用 Redis，不用本地内存</h3>
 * <p>主服务是多实例部署的。本地限流的实际配额 = 实例数 × 单机配额，
 * 加机器就等于放开限流 —— 那是假限流。
 * Redis 是几个实例共用的同一个计数器，才真的控得住。
 *
 * <h3>为什么整段用 Lua</h3>
 * <p>"数一下有多少条 → 没超就加一条"是两个动作，分开做会有竞态：
 * 并发 10 个请求同时数到 9（上限 10），然后全都加了进去，实际放进 19 条。
 * Lua 脚本在 Redis 里一次性执行完，中间不会被别的请求插进来。
 *
 * <h3>⚠️ 查不到就放行（fail-open）</h3>
 * <p>Redis 挂掉时这里返回 true（允许通过），而不是拒绝。
 * 限流是<b>保护机制</b>，不是必须拦住的门禁 ——
 * 因为 Redis 抖一下就把所有人的提交全挡了，那是把保护变成了故障。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    /**
     * 滑动窗口：清掉窗口外的旧记录 → 数一数 → 没到上限就记一条。
     *
     * KEYS[1] 窗口的 key
     * ARGV[1] 当前时间戳（毫秒）
     * ARGV[2] 窗口长度（毫秒）
     * ARGV[3] 窗口内允许的次数
     * ARGV[4] 这一次的唯一标识（有序集合的成员，必须不重复）
     */
    private static final String SLIDING_WINDOW_LUA = """
            local key    = KEYS[1]
            local now    = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit  = tonumber(ARGV[3])
            local member = ARGV[4]

            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            local count = redis.call('ZCARD', key)
            if count < limit then
              redis.call('ZADD', key, now, member)
              redis.call('PEXPIRE', key, window)
              return 1
            end
            return 0
            """;

    private static final DefaultRedisScript<Long> SLIDING_WINDOW =
            new DefaultRedisScript<>(SLIDING_WINDOW_LUA, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 试着拿一个名额。
     *
     * @param key    限流的维度，比如 "submit:user:42"
     * @param limit  窗口内最多允许几次
     * @param window 窗口长度
     * @return true = 拿到了（放行）；false = 超了（应拒绝）
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        if (limit <= 0) {
            return true;   // 不限制
        }
        long now = System.currentTimeMillis();
        // 有序集合的成员必须唯一，否则同一个时间戳的第二次请求会被当成已存在而记不进去
        String member = now + "-" + UUID.randomUUID();
        try {
            Long result = stringRedisTemplate.execute(
                    SLIDING_WINDOW,
                    List.of(key),
                    String.valueOf(now),
                    String.valueOf(window.toMillis()),
                    String.valueOf(limit),
                    member);
            return result != null && result == 1L;
        } catch (Exception e) {
            // 见类注释：Redis 出问题时放行，不要让保护机制变成故障源
            log.warn("限流检查失败，本次放行 key={}", key, e);
            return true;
        }
    }
}
