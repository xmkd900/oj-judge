package com.ojjudge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 提交代码。
 */
@Data
public class QuestionSubmitAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "题目 id 不能为空")
    private Long questionId;

    /**
     * 编程语言：java / cpp / go
     */
    @NotBlank(message = "编程语言不能为空")
    private String language;

    @NotBlank(message = "代码不能为空")
    private String code;
}
