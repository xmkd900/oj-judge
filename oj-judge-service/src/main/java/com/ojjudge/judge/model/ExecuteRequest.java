package com.ojjudge.judge.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 交给代码沙箱执行的请求。
 *
 * 它不含题目 id、不含用户信息</b> —— 沙箱只管"一段代码 + 一组输入 → 一堆输出"。
 * 职责单一，将来换成远程沙箱服务时，这个对象可以直接序列化成 HTTP body。
 */
@Data
public class ExecuteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 编程语言：java / cpp / go
     */
    private String language;

    /**
     * 用户提交的源代码
     */
    private String code;

    /**
     * 每个用例的输入，下标与用例序号一一对应
     */
    private List<String> inputs;

    /**
     * 单个用例的时间上限（毫秒）
     */
    private Long timeLimit;

    /**
     * 内存上限（字节）
     */
    private Long memoryLimit;
}
