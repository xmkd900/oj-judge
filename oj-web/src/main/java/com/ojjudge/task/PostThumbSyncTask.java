package com.ojjudge.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ojjudge.cache.PostThumbCounter;
import com.ojjudge.entity.Post;
import com.ojjudge.entity.PostThumb;
import com.ojjudge.mapper.PostMapper;
import com.ojjudge.mapper.PostThumbMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 点赞数校准（兜底修复，不在主路径上）。
 *
 * <h3>它的定位</h3>
 * <p>正常展示走 Redis 计数器，这里只在后台悄悄对齐一下 ——
 * 所以<b>不要依赖它来保证正确性</b>。它出任何问题都不该影响用户点赞。
 *
 * <h3>为什么需要它</h3>
 * <p>Redis 里的计数是"快照"，可能出现偏差：
 * 写 Redis 时网络抖了一下、Redis 重启丢数据、或者历史遗留的脏值。
 * 关系表 {@code post_thumb} 才是最终依据，
 * 所以定期按关系表重新数一遍，把偏差抹平。
 *
 * <h3>帖子多了怎么办</h3>
 * <p>不是扫全表，而是<b>只处理"本期计数动过"的帖子</b>：
 * 点赞时会往 {@code post:thumb:dirty} 里丢一个 id（见
 * {@link PostThumbCounter#increase}），这里用 {@code SPOP} 分批取。
 * 于是校准的代价只和"有多少帖子被点过赞"有关，
 * 而不是"库里一共有多少帖子" —— 帖子涨到十万也不会拖垮它。
 *
 * <p>另外每批有上限，一次最多处理这么多，剩下的等下一轮。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostThumbSyncTask {

    private final PostThumbCounter postThumbCounter;

    private final PostThumbMapper postThumbMapper;

    private final PostMapper postMapper;

    /**
     * 校准。
     *
     * <p>⚠️ 整个方法包在 try-catch 里：这是后台兜底任务，
     * 它失败绝不能影响主流程，更不能把异常抛到调度线程外导致后续任务全停。
     */
    @Scheduled(fixedDelayString = "${post.thumb-sync.delay-ms:3600000}",
            initialDelayString = "${post.thumb-sync.initial-delay-ms:60000}")
    public void sync() {
        try {
            doSync();
        } catch (Exception e) {
            log.error("点赞数校准任务执行失败，下一轮会重试", e);
        }
    }

    private void doSync() {
        Set<Long> postIds = postThumbCounter.takeDirty(batchSize());
        if (postIds.isEmpty()) {
            return;
        }

        // 一次 SQL 把这一批的准确值全数出来，而不是逐条 COUNT
        Map<Long, Long> correct = new HashMap<>();
        List<Map<String, Object>> rows = postThumbMapper.selectMaps(
                new QueryWrapper<PostThumb>()
                        .select("post_id", "COUNT(*) AS cnt")
                        .in("post_id", postIds)
                        .groupBy("post_id"));
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Object pid = row.get("post_id");
                Object cnt = row.get("cnt");
                if (pid != null) {
                    correct.put(((Number) pid).longValue(),
                            cnt == null ? 0L : ((Number) cnt).longValue());
                }
            }
        }
        // 一个赞都没有的帖子不会出现在结果里
        for (Long id : postIds) {
            correct.putIfAbsent(id, 0L);
        }

        for (Map.Entry<Long, Long> e : correct.entrySet()) {
            // 修正 Redis 里的展示值
            postThumbCounter.fix(e.getKey(), e.getValue());
            // 顺带把冗余列也对齐，它是 Redis 全挂时的兜底数据源
            updateThumbNumColumn(e.getKey(), e.getValue());
        }
        log.info("点赞数校准完成：{} 个帖子", correct.size());
    }

    /**
     * 回写 {@code post.thumb_num}。
     *
     * <p>这一列只是<b>兜底</b>（Redis 完全不可用时直接读它），
     * 主路径不看它，所以写失败也无妨，记一笔日志就行。
     */
    private void updateThumbNumColumn(Long postId, long value) {
        try {
            Post update = new Post();
            update.setId(postId);
            update.setThumbNum((int) Math.max(value, 0));
            postMapper.updateById(update);
        } catch (Exception e) {
            log.warn("回写 post.thumb_num 失败，postId={}", postId, e);
        }
    }

    /**
     * 每批最多处理多少个帖子。剩下的留给下一轮，避免一次拉太多把数据库压住。
     */
    private static final int BATCH_SIZE = 500;

    private int batchSize() {
        return BATCH_SIZE;
    }
}
