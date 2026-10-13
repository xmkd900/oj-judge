package com.ojjudge.dto;

import com.ojjudge.common.SortablePageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * 题目列表查询（公开接口，返回脱敏 VO）。
 *
 * <p>继承 {@link SortablePageRequest} 因为它<b>支持排序</b>，
 * 前端没传时后端默认按创建时间倒序。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuestionQueryDTO extends SortablePageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 按 id 精确过滤
     */
    private Long id;

    /**
     * 标题模糊查询
     */
    private String title;

    /**
     * 标签过滤
     */
    private List<String> tags;

    /**
     * 多标签的匹配方式：and = 同时含全部标签（交集，越筛越窄，默认）；
     * or = 含任意一个即可（并集）
     */
    private String tagMatchMode = com.ojjudge.service.QuestionTagService.MODE_AND;
}
