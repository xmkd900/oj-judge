package com.ojjudge.judge.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 沙箱执行结果。
 *
 * <p>它只是"跑出来的原始数据"，<b>不负责判定对错</b> ——
 * 对错由 JudgeService 拿 expected 比对后决定。
 */
@Data
public class ExecuteResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 每个用例的标准输出，下标对应用例序号
 */
    private List<String> outputs;

    /**
     * 每个用例的运行耗时（毫秒）。含容器内 JVM 启动开销
 */
    private List<Long> times;

    /**
     * 进程退出码
 */
    private Integer exitCode;

    /**
     * 结论
 */
    private JudgeStatus status;

    /**
     * 编译/运行时错误输出（stderr）
 */
    private String errorMessage;

    public static ExecuteResult success(List<String> outputs, List<Long> times, Integer exitCode) {
        ExecuteResult result = new ExecuteResult();
        result.setOutputs(outputs);
        result.setTimes(times);
        result.setExitCode(exitCode);
        result.setStatus(JudgeStatus.ACCEPTED);
        return result;
    }

    public static ExecuteResult fail(JudgeStatus status, String errorMessage) {
        ExecuteResult result = new ExecuteResult();
        result.setStatus(status);
        result.setErrorMessage(errorMessage);
        return result;
    }
}
