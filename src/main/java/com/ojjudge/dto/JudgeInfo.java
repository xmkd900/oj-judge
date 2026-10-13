package com.ojjudge.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 判题结果。库里以 JSON 字符串存于 question_submit.judge_info。
 */
@Data
public class JudgeInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 判题结论。取值中英文混杂（Accepted / Wrong Answer / 编译错误 / 超时 ...），
     * 判断通过只能用 {@code "Accepted".equals(message)}，不要按英文枚举匹配。
     */
    private String message;

    /**
     * 运行耗时（毫秒）
     */
    private Long time;

    /**
     * 内存占用。判题沙箱尚未接入，恒为 null
     */
    private Long memory;
}
