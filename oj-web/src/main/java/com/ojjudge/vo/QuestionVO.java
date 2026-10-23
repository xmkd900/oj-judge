package com.ojjudge.vo;

import com.ojjudge.dto.JudgeConfig;
import com.ojjudge.dto.SampleCase;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class QuestionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    /**
     * 做题页题面（Markdown）
     */
    private String content;

    /**
     * 示例（样例），数组。题面下方展示，帮做题的人理解输入/输出格式
     */
    private List<SampleCase> samples;

    private List<String> tags;       // 数组，不是 JSON 字符串

    private Integer submitNum;       // 通过率分母

    private Integer acceptedNum;     // 通过率分子

    private JudgeConfig judgeConfig; // 做题页顶部显示的时间/内存限制（对象，不是 JSON 字符串）

    private LocalDateTime createTime;
}
