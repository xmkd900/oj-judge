package com.ojjudge.vo;

import com.ojjudge.dto.JudgeInfo;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 提交记录脱敏视图。
 */
@Data
public class QuestionSubmitVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long questionId;

    private Long userId;

    private String language;

    /**
     * 0 等待中 / 1 判题中 / 2 成功 / 3 失败
     */
    private Integer status;

    /**
     * 判题详情，对象（库里存的是 JSON 字符串）
     */
    private JudgeInfo judgeInfo;

    /**
     * 提交的源代码。
     *
     * <p>字段本身保留在契约里，但只有<b>提交者本人或管理员</b>能拿到值，
     * 其余情况一律置为 null —— 这是接口的权限语义，不是"前端用不用"的问题。
     */
    private String code;

    private LocalDateTime createTime;
}
