package com.ojjudge.judge;

import com.ojjudge.judge.model.ExecuteRequest;
import com.ojjudge.judge.model.ExecuteResult;

/**
 * 代码沙箱。
 *
 * <p>只做一件事：<b>给一段代码和一组输入，跑出输出</b>。
 * 它不知道题目、不知道用户、不判定对错 —— 这些是 JudgeService 的事。
 *
 * <p>抽象出这个接口是为了让"代码在哪跑"可替换：
 * <ul>
 *   <li>{@code DockerCodeSandbox} —— 起容器执行（真实项目做法）</li>
 *   <li>将来可加远程沙箱（HTTP 调独立判题服务）、第三方沙箱 API</li>
 * </ul>
 * 换实现时上层业务代码零改动。
 *
 * <p>⚠️ 无论哪种实现，都<b>绝对不要</b>在主后端进程里直接编译执行用户代码：
 * 用户代码是不可信输入，一句 {@code System.exit(0)} 就能把你自己的服务干掉。
 */
public interface CodeSandbox {

    /**
     * 执行代码
     *
     * @param request 语言、源码、每个用例的输入
     * @return 每个用例的输出与耗时；失败时 message 说明原因
     */
    ExecuteResult execute(ExecuteRequest request);
}
