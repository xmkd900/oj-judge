package com.ojjudge.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 一道题的<b>示例</b>（样例），对应洛谷题面里的"输入样例 / 输出样例"。
 *
 * <p>它和 {@link JudgeCase} 是两回事，不要混用：
 * <table>
 *   <tr><th></th><th>示例 SampleCase</th><th>判题用例 JudgeCase</th></tr>
 *   <tr><td>看的人</td><td>所有做题的人</td><td>只有判题程序</td></tr>
 *   <tr><td>数量</td><td>1~3 个</td><td>可能几十个（含边界数据）</td></tr>
 *   <tr><td>是否公开</td><td><b>公开</b>，随 VO 返回</td><td>保密，只有作者/管理员能拿到</td></tr>
 *   <tr><td>作用</td><td>帮人理解题意</td><td>真正用来判定对错</td></tr>
 * </table>
 *
 * <p>分离的理由很实在：如果把判题用例当示例公开，边界数据（比如超大输入、
 * 特殊字符）就泄露了，题目也就失去区分度；反过来示例通常只有一两个，
 * 也扛不住"判题"这件事。
 */
@Data
public class SampleCase implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 输入样例
     */
    private String input;

    /**
     * 输出样例
     */
    private String output;

    /**
     * 样例说明，可空。比如"1 和 2 相加得到 3"
     */
    private String explanation;
}
