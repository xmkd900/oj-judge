package com.ojjudge.dto;

import com.ojjudge.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 帖子列表查询。
 *
 * <p>只按时间倒序，不支持自定义排序 —— 所以继承 {@link PageRequest}
 * （只管翻页）而不是 {@link com.ojjudge.common.SortablePageRequest}。
 * 契约上不声明后端不读的字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostQueryDTO extends PageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 查哪道题下面的帖子
     */
    private Long questionId;
}
