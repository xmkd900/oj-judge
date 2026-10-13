package com.ojjudge.common;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 支持排序的分页请求基类。
 *
 * <p>只有后端真正实现了排序逻辑的接口才继承它
 * （比如题目列表、提交记录），否则老老实实用 {@link PageRequest}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SortablePageRequest extends PageRequest {

    /**
     * 排序字段（camelCase，如 createTime）。
     * 取值必须是白名单内的字段，否则后端会忽略 —— 这是防 SQL 注入的关键。
     */
    private String sortField;

    /**
     * 排序顺序：ascend / descend。默认倒序，列表类接口通常希望"最新的在前"
     */
    private String sortOrder = CommonConstant.SORT_ORDER_DESC;
}
