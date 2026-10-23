package com.ojjudge.dto;

import com.ojjudge.common.SortablePageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * 管理端题目查询（返回完整实体，含答案与测试用例）。
 *
 * <p>单独成一个类而不是复用 {@link QuestionQueryDTO}，是因为这是管理员通道，
 * 能力边界不同 —— 以后要加"按出题人筛选"就加在这里，不会影响到公开接口。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuestionAdminQueryDTO extends SortablePageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    private List<String> tags;

    /**
     * 多标签匹配方式：and（默认，交集）/ or（并集）
     */
    private String tagMatchMode = com.ojjudge.service.QuestionTagService.MODE_AND;

    /**
     * 按出题人过滤，仅管理员可用
     */
    private Long userId;
}
