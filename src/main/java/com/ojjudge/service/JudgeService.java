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

    /**
     * 兜底：把一条提交标记成「判题服务异常」。
     *
     * <p>只在判题流程<b>自己崩了</b>（而不是代码判出来有错）的时候调用 ——
     * 比如数据库连不上、题目数据解析失败、写回结果时出错。
     * 这些异常 {@link #doJudge} 自己接不住，但如果不做任何处理，
     * 提交记录会<b>永远停在 status=0（等待中）</b>，用户看到的就是一直转圈。
     *
     * <p>为什么单独开一个方法而不是让调用方自己 update：
     * 状态怎么算失败、judgeInfo 该长什么样，属于判题服务的知识，不该散到外面去。
     *
     * @param questionSubmitId 提交记录 id
     * @param reason           失败原因，会写进 judgeInfo.errorMessage 便于排查
     */
    void markJudgeFailed(Long questionSubmitId, String reason);
}
