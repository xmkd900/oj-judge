package com.ojjudge.common;

import lombok.Data;

/**
 * 分页请求基类：只管翻页。
 *
 * <p>排序字段不在这里 —— 它们在 {@link SortablePageRequest} 里。
 * 这样"支持排序的接口"和"不支持排序的接口"在字段清单上就能区分开，
 * 不会出现"声明了 sortField 但后端根本不读"这种骗人的契约。
 */
@Data
public class PageRequest {

    /**
     * 当前页号
     */
    private long current = 1;

    /**
     * 页面大小
     */
    private long pageSize = 10;
}
