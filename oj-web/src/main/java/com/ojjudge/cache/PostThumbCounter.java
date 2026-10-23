package com.ojjudge.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ojjudge.entity.PostThumb;
import com.ojjudge.mapper.PostThumbMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 帖子点赞数的读写。
 *
 * <h3>为什么点赞数不走"列表缓存"</h3>
 * <p>它和正文、作者这些信息不一样：正文几乎不变，点赞数每次点赞都要动。
 * 塞进同一个缓存对象的话，谁点个赞整个列表缓存就得重建 —— 缓存等于白做。
 * 所以点赞数单独放 Redis，列表缓存里<b>不含</b>它（见 {@link PostListItem}）。
 *
 * <h3>谁说了算</h3>
 * <p><b>关系表 {@code post_thumb} 是最终依据</b>，Redis 里的是"用于展示的快照"。
 * 这么做的好处是数据源只有一份，不会出现"两个地方数字不一样还不知道信谁"。
 * 即便 Redis 里的计数全丢了，也能从关系表 COUNT 回来。
 *
 * <h3>帖子多了会怎样</h3>
 * <ul>
 *   <li><b>读</b>：一页只有 10 条，一次 {@code MGET} 取 10 个 key。
 *       代价只和页大小有关，和总帖子数无关。</li>
 *   <li><b>内存</b>：计数 key 带 7 天 TTL，长期没人点赞的冷帖子会自动过期，
 *       下次有人看再回填。Redis 里只留活跃的那部分。</li>
 *   <li><b>回填</b>：缓存里缺多少条就补多少条，而且是<b>一条 SQL 批量 GROUP BY</b>，
 *       不是逐条 COUNT。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostThumbCounter {

    /**
     * 单个帖子的点赞数：post:thumb:{postId}
     */
    private static final String KEY_PREFIX = "post:thumb:";

    /**
     * 本期计数发生过变动的帖子 id 集合，校准任务据此只处理"活跃"的帖子。
     */
    private static final String DIRTY_KEY = "post:thumb:dirty";

    /**
     * 计数 key 的存活时间。过期只是释放内存，不代表数据丢了 ——
     * 缺失时会从关系表重新数一遍。
     */
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate stringRedisTemplate;

    private final PostThumbMapper postThumbMapper;

    // ------------------------------------------------------------------ 读

    /**
     * 批量取点赞数。
     *
     * @return postId → 点赞数；查不到的帖子不会出现在 map 里
     */
    public Map<Long, Long> getBatch(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = postIds.stream().distinct().toList();
        Map<Long, Long> result = new HashMap<>();

        // 1. 先试着从 Redis 批量取
        List<Long> missing = new ArrayList<>();
        try {
            List<String> keys = ids.stream().map(this::key).toList();
            List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
            if (values != null) {
                for (int i = 0; i < ids.size(); i++) {
                    String v = values.get(i);
                    if (v != null) {
                        try {
                            result.put(ids.get(i), Long.parseLong(v));
                        } catch (NumberFormatException e) {
                            missing.add(ids.get(i));
                        }
                    } else {
                        missing.add(ids.get(i));
                    }
                }
            } else {
                missing.addAll(ids);
            }
        } catch (Exception e) {
            // Redis 不可用：整个降级到关系表。展示会慢一点，但数字是准的。
            log.warn("读取点赞数缓存失败，本次直接从关系表统计", e);
            return countFromDb(ids);
        }

        // 2. 缓存里没有的（过期了 / 第一次访问），批量从关系表数一遍再回填
        if (!missing.isEmpty()) {
            Map<Long, Long> fromDb = countFromDb(missing);
            result.putAll(fromDb);
            warmUp(fromDb);
        }
        return result;
    }

    // ------------------------------------------------------------------ 写

    /**
     * 点赞数 +1。
     *
     * <p>调用方必须<b>先成功往关系表插了记录</b>才能调这里 ——
     * 关系表才是权威，Redis 只是跟着动一下。
     */
    public void increase(Long postId) {
        change(postId, 1);
    }

    /**
     * 点赞数 -1。同样，调用方得先从关系表删掉记录。
     */
    public void decrease(Long postId) {
        change(postId, -1);
    }

    private void change(Long postId, long delta) {
        if (postId == null) {
            return;
        }
        try {
            Long value = stringRedisTemplate.opsForValue().increment(key(postId), delta);
            // 减到负数说明计数早就偏了（比如 Redis 里的数据丢过），顺手纠正成 0
            if (value != null && value < 0) {
                stringRedisTemplate.opsForValue().set(key(postId), "0", TTL);
            }
            stringRedisTemplate.expire(key(postId), TTL);
            // 记一笔"这个帖子的计数动过"，校准任务只处理这些
            stringRedisTemplate.opsForSet().add(DIRTY_KEY, String.valueOf(postId));
        } catch (Exception e) {
            // Redis 写不进去没关系：关系表已经落库了，数字丢不了，
            // 下次读取时会重新数一遍，校准任务也会补上
            log.warn("更新点赞数缓存失败，postId={}（关系表已落库，不会丢）", postId, e);
        }
    }

    /**
     * 缓存刚回填好时直接写入，用于懒加载。
     */
    private void warmUp(Map<Long, Long> counts) {
        if (counts.isEmpty()) {
            return;
        }
        try {
            Map<String, String> kv = new HashMap<>();
            for (Map.Entry<Long, Long> e : counts.entrySet()) {
                kv.put(key(e.getKey()), String.valueOf(e.getValue()));
            }
            stringRedisTemplate.opsForValue().multiSet(kv);
            for (String k : kv.keySet()) {
                stringRedisTemplate.expire(k, TTL);
            }
        } catch (Exception e) {
            log.debug("回填点赞数缓存失败（不影响本次返回）", e);
        }
    }

    // ------------------------------------------------------------------ 校准任务用

    /**
     * 取一批"计数动过"的帖子 id，供定时任务校准。
     *
     * <p>用 {@code SPOP} 而不是"取出来再删"：后者在处理期间新增的变动
     * 会被一起删掉，那部分就永远没机会校准了。
     */
    public Set<Long> takeDirty(int max) {
        Set<Long> ids = new HashSet<>();
        try {
            List<String> popped = stringRedisTemplate.opsForSet().pop(DIRTY_KEY, max);
            if (popped != null) {
                for (String s : popped) {
                    try {
                        ids.add(Long.parseLong(s));
                    } catch (NumberFormatException ignored) {
                        // 脏数据，忽略
                    }
                }
            }
        } catch (Exception e) {
            log.warn("取待校准的帖子列表失败", e);
        }
        return ids;
    }

    /**
     * 用关系表里数出来的准确值覆盖缓存（校准用）。
     */
    public void fix(Long postId, long correct) {
        try {
            stringRedisTemplate.opsForValue().set(
                    key(postId), String.valueOf(Math.max(correct, 0)), TTL);
        } catch (Exception e) {
            log.warn("校准点赞数失败，postId={}", postId, e);
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 从关系表统计点赞数。
     *
     * <p>一条 SQL 用 GROUP BY 拿回全部，不是逐条 COUNT ——
     * 一页 10 条帖子就等于 10 次 COUNT，那才是真的会把数据库拖垮。
     */
    private Map<Long, Long> countFromDb(Collection<Long> postIds) {
        Map<Long, Long> result = new HashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return result;
        }
        List<Long> ids = postIds.stream().distinct().toList();
        try {
            List<Map<String, Object>> rows = postThumbMapper.selectMaps(
                    new QueryWrapper<PostThumb>()
                            .select("post_id", "COUNT(*) AS cnt")
                            .in("post_id", ids)
                            .groupBy("post_id"));
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object pid = row.get("post_id");
                    Object cnt = row.get("cnt");
                    if (pid != null) {
                        result.put(((Number) pid).longValue(),
                                cnt == null ? 0L : ((Number) cnt).longValue());
                    }
                }
            }
            // 一个赞都没有的帖子不会出现在 GROUP BY 结果里，补 0
            for (Long id : ids) {
                result.putIfAbsent(id, 0L);
            }
        } catch (Exception e) {
            log.error("统计点赞数失败", e);
            for (Long id : ids) {
                result.putIfAbsent(id, 0L);
            }
        }
        return result;
    }

    private String key(Long postId) {
        return KEY_PREFIX + postId;
    }
}
