package com.ojjudge.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.ojjudge.entity.QuestionTag;
import com.ojjudge.vo.TagVO;

import java.util.List;

/**
 * 题目标签。
 *
 * <p>独立成一个 Service 是为了把"标签怎么存"这件事隔离住 ——
 * 以后若要换成标签字典表、加同义词、加分类，只改这里，
 * 上层 QuestionService 和前端都不用动（VO 契约是 List&lt;String&gt;）。
 */
public interface QuestionTagService extends IService<QuestionTag> {

    /**
     * 全量替换某道题的标签（先删后插）
     */
    void replaceTags(Long questionId, List<String> tags);

    /**
     * 删除某道题的全部标签
     */
    void removeByQuestionId(Long questionId);

    /**
     * 查询某道题的标签
     */
    List<String> listTagsByQuestionId(Long questionId);

    /**
     * 批量查询多道题的标签，key 是题目 id
     */
    java.util.Map<Long, List<String>> listTagsByQuestionIds(List<Long> questionIds);

    /**
     * 按标签筛选题目 id。
     *
     * @param tags      标签列表
     * @param matchMode and = 必须同时含全部标签（交集，越筛越窄）；or = 含任意一个即可
     * @return 命中的题目 id；标签为空时返回 null 表示"不加这个条件"
     */
    List<Long> listQuestionIdsByTags(List<String> tags, String matchMode);

    /**
     * 全部已使用的标签及使用次数，供前端下拉选择。按使用次数倒序。
     */
    List<TagVO> listAllTags();

    /**
     * 交集模式
     */
    String MODE_AND = "and";

    /**
     * 并集模式
     */
    String MODE_OR = "or";
}
