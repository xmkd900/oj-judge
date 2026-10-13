package com.ojjudge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 更新题目（管理员通道，不校验归属）。
 *
 * <p>结构和 {@link QuestionEditDTO} 一样却分成两个类，是因为两者的<b>权限校验逻辑不同</b>，
 * 将来字段也可能分化（比如管理员通道允许改 userId）。
 * 共用一个类的话，改一个字段会同时影响两条权限链路。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuestionUpdateDTO extends QuestionAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "题目 id 不能为空")
    private Long id;
}
