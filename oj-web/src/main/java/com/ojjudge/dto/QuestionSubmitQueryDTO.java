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

    /**
     * 按提交记录主键精确查。
     *
     * <p>前端提交后要轮询这一条的状态，必须能按 id 定位；
     * 之前前端传了 {@code id} 但后端没有这个字段，条件被静默忽略，
     * 于是退化成"取最新 10 条再本地 find" —— 数据一多就会查不到。
     */
    private Long id;

    private Long questionId;

    private String language;

    /**
     * 0 等待中 / 1 判题中 / 2 成功 / 3 失败
     */
    private Integer status;

    /**
     * 按提交者筛选。<b>仅管理员生效</b> ——
     * 普通用户传什么都只会看到自己的（见 QuestionServiceImpl 的收窄逻辑）。
     */
    private Long userId;
}
