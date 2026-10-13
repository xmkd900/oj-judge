package com.ojjudge.dto;

import com.ojjudge.common.SortablePageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 提交记录查询。
 *
 * <p>支持排序：前端固定传 sortField=createTime、sortOrder=descend，
 * 这样最新的提交排在最前面。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuestionSubmitQueryDTO extends SortablePageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long questionId;

    private String language;

    /**
     * 0 等待中 / 1 判题中 / 2 成功 / 3 失败
     */
    private Integer status;

    private Long userId;
}
