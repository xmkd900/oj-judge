package com.ojjudge.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 标签及其使用次数。供前端下拉选择用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tag;

    /**
     * 有多少道题用了这个标签
     */
    private Long count;
}
