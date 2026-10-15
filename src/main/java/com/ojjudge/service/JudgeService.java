package com.ojjudge.service;

import com.ojjudge.dto.JudgeInfo;

/**
 * 判题服务。
 *
 * <p>职责边界：
 * <ul>
 *   <li>它知道<strong>题目</strong>——取出 judgeCase / judgeConfig</li>
 *   <li>它决定<strong>对错</strong>——拿用户输出比对期望输出</li>
 *   <li>它<strong>不</strong>关心代码在哪跑——那是 CodeSandbox 的事</li>
 * </ul>
 */
public interface JudgeService {

    /**
     * 对一条提交记录执行判题，并把结果与 judgeInfo 写回数据库
     *
     * @param questionSubmitId 提交记录 id
     * @return 判题结论
     */
    JudgeInfo doJudge(Long questionSubmitId);
}
