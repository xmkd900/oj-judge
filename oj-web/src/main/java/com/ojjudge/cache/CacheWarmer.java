package com.ojjudge.cache;

import com.ojjudge.dto.QuestionQueryDTO;
import com.ojjudge.service.QuestionService;
import com.ojjudge.service.QuestionTagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动后把缓存填上，别让第一个访问者当小白鼠。
 *
 * <p>预热的是"人人都会看、且几乎不变"的两份数据：
 * 标签列表、题目列表首屏。首屏预热会顺带把这一页的题目详情也写进缓存，
 * 因为 {@code listQuestionVoByPage} 内部会按 id 取详情（见
 * {@link QuestionCacheService#getQuestionVoBatch}）。
 *
 * <p>只预热首屏而不预热所有题目：题目详情页按需缓存即可，
 * 全量预热会在启动时打出一堆一次性查询，收益却很低。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheWarmer {

    private final QuestionTagService questionTagService;

    private final QuestionService questionService;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        warmUp("标签列表", () -> questionTagService.listAllTags());
        warmUp("题目列表首屏", () -> questionService.listQuestionVoByPage(new QuestionQueryDTO()));
    }

    /**
     * 预热失败绝不能阻止启动 —— 最坏情况只是第一个用户慢一点。
     * Redis 没起来时尤其重要：不能因为缓存不可用就让整个应用起不来。
     */
    private void warmUp(String what, Runnable action) {
        try {
            action.run();
            log.info("缓存预热完成：{}", what);
        } catch (Exception e) {
            log.warn("缓存预热失败：{}，第一个访问者会慢一点，不影响功能", what, e);
        }
    }
}
