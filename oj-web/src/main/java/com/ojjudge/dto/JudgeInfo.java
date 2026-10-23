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

    /**
     * 编译 / 运行时的错误输出（stderr），只在失败时才有值。
     *
     * <p>为什么必须有这个字段：只告诉用户"编译错误"等于什么都没说。
     * 最常见的失败原因是<strong>类名不是 Main</strong>（比如从 LeetCode 复制来的
     * {@code class Solution}），而 javac 的报错信息里写得很清楚。
     * 不把这个透传出去，用户根本没法自查。
     */
    private String errorMessage;
}
