package com.ojjudge.judge.model;

/**
 * 判题结论。
 *
 * <p>注意：这里的代码值最终会写进 {@code judgeInfo.message}，
 * 而前端判断通过与否只用 {@code message === "Accepted"}。
 * 所以 <b>{@link #ACCEPTED} 的 message 必须严格等于 "Accepted"</b>。
 */
public enum JudgeStatus {

    /**
     * 全部用例通过
 */
    ACCEPTED("Accepted"),

    /**
     * 输出与答案不一致
 */
    WRONG_ANSWER("Wrong Answer"),

    /**
     * 编译阶段失败
 */
    COMPILE_ERROR("编译错误"),

    /**
     * 超过时间限制
 */
    TIME_LIMIT_EXCEEDED("超时"),

    /**
     * 内存超限
 */
    MEMORY_LIMIT_EXCEEDED("内存超限"),

    /**
     * 命中危险 API 黑名单，还没送进容器就被拦下
 */
    DANGEROUS("代码包含危险操作"),

    /**
     * 运行期异常 / 非零退出
 */
    RUNTIME_ERROR("运行错误"),

    /**
     * 沙箱不可用
 */
    SYSTEM_ERROR("判题服务异常"),
    ;

    private final String message;

    JudgeStatus(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
