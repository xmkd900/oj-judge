package com.ojjudge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 发帖请求。
 */
@Data
public class PostAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 要发在哪道题下面
     */
    @NotNull(message = "缺少题目 id")
    private Long questionId;

    /**
     * 标题，可选
     */
    @Size(max = 128, message = "标题不能超过 128 字")
    private String title;

    /**
     * 正文，纯文本
     */
    @NotBlank(message = "内容不能为空")
    @Size(max = 5000, message = "内容不能超过 5000 字")
    private String content;
}
