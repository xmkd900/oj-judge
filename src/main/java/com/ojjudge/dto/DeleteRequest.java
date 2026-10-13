package com.ojjudge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 按 id 删除。题目删除和用户删除都复用它 —— 结构就是 {@code {id}}，没有额外攻击面。
 */
@Data
public class DeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "id 不能为空")
    private Long id;
}
