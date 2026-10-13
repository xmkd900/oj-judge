package com.ojjudge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 题可写字段的公共部分：创建（add）、编辑（edit）、管理员更新（update）都继承它。
 *
 * <p>刻意不含 userId / submitNum / acceptedNum —— 这些由服务端设置，
 * 客户端传了也无效，不写进 DTO 可以从源头杜绝伪造。
 *
 * <p><b>校验分组</b>：title / content 上的 {@code @NotBlank} 只挂在 {@link OnCreate} 分组上，
 * 所以只有"创建"时才强制必填；编辑和更新走默认分组，所有字段都可选 ——
 * 这样才能真正做到"没传 = 不想改"（见 QuestionServiceImpl#applyUpdate）。
 */
@Data
public class QuestionAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 仅用于创建场景的校验分组。
     * Controller 上写 {@code @Validated(QuestionAddDTO.OnCreate.class)} 才会生效。
     */
    public interface OnCreate {
    }

    @NotBlank(message = "标题不能为空", groups = OnCreate.class)
    private String title;

    @NotBlank(message = "内容不能为空", groups = OnCreate.class)
    private String content;

    private List<String> tags;

    /**
     * 示例（样例），公开给所有做题的人。建议 1~3 个
     */
    private List<SampleCase> samples;

    /**
     * 题解，敏感：只有作者本人和管理员能看到
     */
    private String answer;

    private List<JudgeCase> judgeCase;

    private JudgeConfig judgeConfig;
}
