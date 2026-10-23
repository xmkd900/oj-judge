package com.ojjudge.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 一个判题用例。库里以 JSON 数组字符串存于 question.judge_case。
 */
@Data
public class JudgeCase implements Serializable {

    private static final long serialVersionUID = 1L;

    private String input;

    private String output;
}
