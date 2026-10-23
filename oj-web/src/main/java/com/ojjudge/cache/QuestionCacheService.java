package com.ojjudge.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ojjudge.common.CommonConstant;
import com.ojjudge.common.ErrorCode;
import com.ojjudge.common.JsonUtils;
import com.ojjudge.common.SortUtils;
import com.ojjudge.dto.JudgeConfig;
import com.ojjudge.dto.QuestionQueryDTO;
import com.ojjudge.dto.SampleCase;
import com.ojjudge.entity.Question;
import com.ojjudge.exception.BusinessException;
import com.ojjudge.mapper.QuesionMapper;
import com.ojjudge.service.QuestionTagService;
import com.ojjudge.vo.QuestionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static org.springframework.beans.BeanUtils.copyProperties;

/**
 * 题目查询 + 缓存。
 *
 * <h3>为什么要单独抽一个类</h3>
 * <p>Spring 的缓存注解是靠 AOP 代理生效的，而<b>同一个类内部的方法互相调用不走代理</b>。
 * 如果把 {@code @Cacheable} 直接标在 {@code QuestionServiceImpl} 上，
 * 列表方法想复用详情方法的缓存就会静默失效 —— 这是最容易踩的坑。
 * 抽成独立的 Bean 之后，所有调用都是跨 Bean 的，注解一定能生效。
 *
 * <h3>两个缓存的分工</h3>
 * <ul>
 *   <li>{@link CacheNames#QUESTION_IDS} —— "哪些题、共几条"，只在题目增删改时失效</li>
 *   <li>{@link CacheNames#QUESTION} —— 单题详情，含通过率，判题回写时失效</li>
 * </ul>
 * 具体见 {@link CacheNames#QUESTION_IDS} 的说明。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionCacheService {

    private static final String DEFAULT_QUESTION_SORT = "createTime";

    private final QuesionMapper questionMapper;

    private final QuestionTagService questionTagService;

    private final JsonUtils jsonUtils;

    private final CacheManager cacheManager;

    /**
     * 查出一页题目的"骨架"：总数 + id 顺序。不带题目内容，所以判题回写不影响它。
     *
     * <p>⚠️ <b>必须指定 {@code keyGenerator}</b>，不能用默认的。
     * 默认会把参数对象本身当 key、最后用它的 {@code toString()} 落到 Redis，
     * 而 Lombok 的 {@code toString()} 不含父类的 {@code current} / {@code pageSize} ——
     * 结果是第 1 页和第 2 页共用同一个 key，翻页永远返回同一份数据。
     * 详见 {@link QuestionQueryKeyGenerator}。
     *
     * <h3>只缓存"没筛选"的查询</h3>
     * <p>{@code condition} 筛掉带标签 / 关键词的查询。这是实测之后做的决定：
     * <pre>
     *   全站标签 17 个 → 1~3 个标签的组合就有 833 种
     *   50 个标签     → 20,875 种
     * </pre>
     * 组合数会随标签数量爆炸式增长，而每种组合被重复访问的概率极低。
     * 实测 30 位访客随机挑标签筛选：<b>骨架命中率只有 6%</b>，却产生了 31 个 key ——
     * 等于每次访问都在制造一个几乎没人会再用第二次的缓存。
     *
     * <p>不筛选的查询就完全不同了：key 只由页数决定，<b>空间有界</b>，
     * 而首页恰恰是所有人共同的入口，命中率很高。
     *
     * <p>用 {@code condition} 而不是 {@code unless}：前者在方法执行<b>前</b>判断，
     * 不满足就直接跳过整套缓存逻辑（连那次注定落空的 Redis GET 都不发）；
     * 后者是在执行后才判断，白查一次缓存。
     *
     * <p>筛选查询不走缓存，就只剩这两条 SQL —— 题目量不大时它们并不贵，
     * 而且筛选查询真正花钱的地方（填充题目详情）依然走 {@link #getQuestionVoBatch} 的缓存。
     */
    @Cacheable(cacheNames = CacheNames.QUESTION_IDS,
            keyGenerator = "questionQueryKeyGenerator",
            condition = "!#queryDTO.hasFilter()",
            sync = true)
    public QuestionIdPage listQuestionIdPage(QuestionQueryDTO queryDTO) {
        Page<Question> page = new Page<>(queryDTO.getCurrent(), queryDTO.getPageSize());
        SortUtils.apply(page, queryDTO.getSortField(), queryDTO.getSortOrder(),
                CommonConstant.QUESTION_SORT_FIELDS, DEFAULT_QUESTION_SORT);

        QueryWrapper<Question> wrapper = new QueryWrapper<>();
        wrapper.eq(queryDTO.getId() != null, "id", queryDTO.getId())
                .like(StringUtils.hasText(queryDTO.getTitle()), "title", queryDTO.getTitle());
        applyTagFilter(wrapper, queryDTO.getTags(), queryDTO.getTagMatchMode());

        Page<Question> result = questionMapper.selectPage(page, wrapper);
        return new QuestionIdPage(result.getTotal(),
                result.getRecords().stream().map(Question::getId).toList());
    }

    /**
     * 单道题的脱敏详情。
     *
     * <p>能缓存的前提：返回值<b>与请求者身份无关</b>（不含 answer / judgeCase，
     * 也不读当前登录用户），否则把一个人的视图缓存给别人看就是数据泄露。
     */
    @Cacheable(cacheNames = CacheNames.QUESTION, key = "#id", sync = true)
    public QuestionVO getQuestionVo(Long id) {
        Question question = questionMapper.selectById(id);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        return toVO(question, questionTagService.listTagsByQuestionId(id));
    }

    /**
     * 按 id 批量取详情，列表页用。
     *
     * <p>这里没有用 {@code @Cacheable} 而是手写缓存读写，原因是<b>批量必须合并成少量查询</b>：
     * 如果逐个调 {@link #getQuestionVo}，冷启动时空缓存会让一页 10 道题变成 20 次 SQL
     * （一次查题目、一次查标签），比原来批量查的 3 次还慢 —— 优化反而变成劣化。
     *
     * <p>所以这里的做法是：命中的直接取，没命中的<b>攒起来一次性批量查</b>，
     * 查完再逐条写回缓存。
     */
    public List<QuestionVO> getQuestionVoBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Cache cache = cacheManager.getCache(CacheNames.QUESTION);

        // 按入参顺序占位，保证返回顺序和分页时的排序一致
        QuestionVO[] slots = new QuestionVO[ids.size()];
        List<Long> missed = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            QuestionVO cached = cache != null ? cache.get(id, QuestionVO.class) : null;
            if (cached != null) {
                slots[i] = cached;
            } else {
                missed.add(id);
            }
        }

        if (!missed.isEmpty()) {
            List<Question> rows = questionMapper.selectList(
                    new QueryWrapper<Question>().in("id", missed));
            Map<Long, List<String>> tagsMap = questionTagService.listTagsByQuestionIds(missed);

            Map<Long, QuestionVO> built = new HashMap<>();
            for (Question q : rows) {
                QuestionVO vo = toVO(q, tagsMap.getOrDefault(q.getId(), List.of()));
                built.put(q.getId(), vo);
                if (cache != null) {
                    cache.put(q.getId(), vo);      // 顺手把这一批填进缓存
                }
            }
            for (int i = 0; i < ids.size(); i++) {
                if (slots[i] == null) {
                    slots[i] = built.get(ids.get(i));
                }
            }
        }

        // 过滤掉查不到的：骨架缓存可能比真实数据旧一拍（题目刚被删掉）
        return Arrays.stream(slots).filter(Objects::nonNull).toList();
    }

    private QuestionVO toVO(Question question, List<String> tags) {
        QuestionVO vo = new QuestionVO();
        copyProperties(question, vo);
        vo.setTags(tags == null || tags.isEmpty() ? List.of() : tags);
        vo.setJudgeConfig(jsonUtils.toObject(question.getJudgeConfig(), JudgeConfig.class));
        vo.setSamples(jsonUtils.toList(question.getSamples(), SampleCase.class));
        return vo;
    }

    /**
     * 标签筛选：先算出命中的题目 id 再拼进 IN 条件，这样 tag 列上的索引才能生效。
     *
     * <p>⚠️ 这段逻辑在 {@code QuestionServiceImpl} 里也有一份（管理员通道在用它）。
     * 改的时候两边都要改 —— 重复是刻意的，抽成公共方法要跨层传 Wrapper，反而更难读。
     */
    private void applyTagFilter(QueryWrapper<Question> wrapper, List<String> tags, String matchMode) {
        List<Long> ids = questionTagService.listQuestionIdsByTags(tags, matchMode);
        if (ids == null) {
            return;                 // 没传标签，不加条件
        }
        if (ids.isEmpty()) {
            wrapper.eq("id", -1L);  // 有标签条件但一条都没命中：直接返回空
            return;
        }
        wrapper.in("id", ids);
    }
}
