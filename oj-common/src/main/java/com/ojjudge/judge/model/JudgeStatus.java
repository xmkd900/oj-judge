package com.ojjudge.judge.model;

/**
 * 判题结论。
 *
 * <p>{@code message} 是<b>给用户看的文案</b>，统一用中文。
 *
 * <p>⚠️ <b>不要用 message 去判断"是否通过"</b> —— 文案是会改的，
 * 拿它做判断等于把业务逻辑绑在显示文字上。
 * 要判断就用 {@code QuestionSubmit.status}（2 = 通过），那才是稳定的数字。
 *
 * <p>（以前这里英文和中文混着写，前端只好拿 {@code message === "Accepted"} 判断，
 * 注释里还得专门警告"这个值不能改"。现在两边都改成基于 status，
 * 文案想怎么改都行。）
 */
public enum JudgeStatus {

    /**
     * 全部用例通过
 */
    ACCEPTED("通过"),

    /**
     * 输出与答案不一致
 */
    WRONG_ANSWER("答案错误"),

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
