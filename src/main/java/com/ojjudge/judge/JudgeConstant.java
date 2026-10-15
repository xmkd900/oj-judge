package com.ojjudge.judge;

/**
 * 判题相关常量。
 */
public interface JudgeConstant {

    /**
     * 全部用例通过。
     *
     * <p>⚠️ 这个值沿用前端已有约定，<b>必须是英文 "Accepted"</b>，
     * 前端 {@code isAccepted()} 就是拿它做等值比较的。
     */
    String ACCEPTED = "Accepted";

    /**
     * 输出与答案不一致
     */
    String WRONG_ANSWER = "Wrong Answer";

    /**
     * 编译阶段失败
     */
    String COMPILE_ERROR = "编译错误";

    /**
     * 超过题目时间限制
     */
    String TIME_LIMIT_EXCEEDED = "超时";

    /**
     * 运行期抛异常 / 非零退出
     */
    String RUNTIME_ERROR = "运行错误";

    /**
     * 命中危险 API 黑名单
     */
    String DANGEROUS_CODE = "代码包含危险操作";

    /**
     * 沙箱本身不可用（比如机器上没装 Docker）
     */
    String SANDBOX_UNAVAILABLE = "判题服务暂不可用";

    /**
     * 内存限制默认值：256 MB
     */
    long DEFAULT_MEMORY_LIMIT = 256L * 1024 * 1024;

    /**
     * 时间限制默认值：1000 ms
     */
    long DEFAULT_TIME_LIMIT = 1000L;
}
