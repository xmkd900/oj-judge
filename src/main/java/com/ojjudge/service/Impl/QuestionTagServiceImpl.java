package com.ojjudge.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ojjudge.entity.QuestionTag;
import com.ojjudge.mapper.QuestionTagMapper;
import com.ojjudge.service.QuestionTagService;
import com.ojjudge.vo.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuestionTagServiceImpl extends ServiceImpl<QuestionTagMapper, QuestionTag>
        implements QuestionTagService {

    private static final int MAX_TAG_LENGTH = 64;

    private static final int MAX_TAGS_PER_QUESTION = 10;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceTags(Long questionId, List<String> tags) {
        // 先清空再插入，比"算差集"简单且不易出错
        this.lambdaUpdate().eq(QuestionTag::getQuestionId, questionId).remove();

        List<String> normalized = normalize(tags);
        if (normalized.isEmpty()) {
            return;
        }
        List<QuestionTag> rows = normalized.stream()
                .map(tag -> {
                    QuestionTag row = new QuestionTag();
                    row.setQuestionId(questionId);
                    row.setTag(tag);
                    row.setCreateTime(LocalDateTime.now());
                    return row;
                })
                .toList();
        this.saveBatch(rows);
    }

    @Override
    public void removeByQuestionId(Long questionId) {
        this.lambdaUpdate().eq(QuestionTag::getQuestionId, questionId).remove();
    }

    @Override
    public List<String> listTagsByQuestionId(Long questionId) {
        return this.lambdaQuery()
                .eq(QuestionTag::getQuestionId, questionId)
                .orderByAsc(QuestionTag::getId)
                .list()
                .stream()
                .map(QuestionTag::getTag)
                .toList();
    }

    @Override
    public Map<Long, List<String>> listTagsByQuestionIds(List<Long> questionIds) {
        Map<Long, List<String>> result = new HashMap<>();
        if (CollectionUtils.isEmpty(questionIds)) {
            return result;
        }
        this.lambdaQuery()
                .in(QuestionTag::getQuestionId, questionIds)
                .orderByAsc(QuestionTag::getId)
                .list()
                .forEach(row -> result
                        .computeIfAbsent(row.getQuestionId(), k -> new ArrayList<>())
                        .add(row.getTag()));
        return result;
    }

    @Override
    public List<Long> listQuestionIdsByTags(List<String> tags, String matchMode) {
        List<String> normalized = normalize(tags);
        if (normalized.isEmpty()) {
            return null;   // 没有标签条件，调用方不该加这个过滤
        }

        // 先按标签捞出所有 (questionId, tag) 记录 —— 这一步走 idx_tag 索引
        List<QuestionTag> rows = this.lambdaQuery()
                .in(QuestionTag::getTag, normalized)
                .list();

        // 按题目分组，统计它命中了几个不同的标签
        Map<Long, Set<String>> hitByQuestion = new HashMap<>();
        for (QuestionTag row : rows) {
            hitByQuestion.computeIfAbsent(row.getQuestionId(), k -> new LinkedHashSet<>()).add(row.getTag());
        }

        boolean and = !MODE_OR.equalsIgnoreCase(matchMode);
        return hitByQuestion.entrySet().stream()
                // AND：命中的标签数必须等于查询的标签数；OR：命中至少一个
                .filter(e -> and ? e.getValue().size() == normalized.size() : !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public List<TagVO> listAllTags() {
        // 全表按 tag 分组统计。标签量远小于题目量，可接受；
        // 若将来标签很多，可以加一张标签计数字段或缓存。
        Map<String, Long> counted = this.list().stream()
                .collect(Collectors.groupingBy(QuestionTag::getTag, Collectors.counting()));
        return counted.entrySet().stream()
                .map(e -> new TagVO(e.getKey(), e.getValue()))
                .sorted((a, b) -> Long.compare(b.getCount(), a.getCount()))
                .toList();
    }

    /**
     * 标签归一化：去空白、去重、截断、限制数量。
     *
     * <p>不要小看这一步 —— 用户手打标签必然出现 "Java" / "java" / " Java "，
     * 不在入库前统一，筛选就永远筛不准。
     */
    private List<String> normalize(List<String> tags) {
        if (CollectionUtils.isEmpty(tags)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) {
                continue;
            }
            String trimmed = tag.trim();
            if (trimmed.length() > MAX_TAG_LENGTH) {
                trimmed = trimmed.substring(0, MAX_TAG_LENGTH);
            }
            set.add(trimmed);
            if (set.size() >= MAX_TAGS_PER_QUESTION) {
                break;
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * 兼容旧调用：保留一个按 id 查的包装，便于排查数据
     */
    public List<QuestionTag> rawListByQuestionId(Long questionId) {
        return this.list(new QueryWrapper<QuestionTag>().eq("question_id", questionId));
    }
}
