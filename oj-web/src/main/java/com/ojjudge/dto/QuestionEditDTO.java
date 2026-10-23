package com.ojjudge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 编辑题目（作者本人或管理员）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuestionEditDTO extends QuestionAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "题目 id 不能为空")
    private Long id;
}
