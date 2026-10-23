package com.ojjudge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 题目实体。
 *
 * <p>字段名必须与数据库列严格对应，每个字段都显式加了 {@code @TableField}。
 * 曾经因为 User 实体里 username 对不上 user_name 导致登录必失败，别再踩一次。
 */
@Data
@TableName("question")
public class Question implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @TableField("id")
    private Long id;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    /**
     * 示例（样例），数据库存 JSON 数组字符串：[{input, output, explanation}]
     *
     * <p>与 judge_case 的区别：示例是<b>公开</b>的，用来帮做题的人理解题意；
     * judge_case 是保密的判题用例。详见 {@code SampleCase} 的注释。
     */
    @TableField("samples")
    private String samples;

    /**
     * 题解，敏感字段，只对作者本人和管理员开放
     */
    @TableField("answer")
    private String answer;

    @TableField("submit_num")
    private Integer submitNum;

    @TableField("accepted_num")
    private Integer acceptedNum;

    /**
     * 判题用例，数据库存 JSON 数组字符串
     */
    @TableField("judge_case")
    private String judgeCase;

    /**
     * 判题配置，数据库存 JSON 对象字符串
     */
    @TableField("judge_config")
    private String judgeConfig;

    @TableField("thumb_num")
    private Integer thumbNum;

    @TableField("favour_num")
    private Integer favourNum;

    @TableField("user_id")
    private Long userId;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_delete")
    @TableLogic
    private Integer isDelete;

    /**
     * 标签。<b>非数据库字段</b> —— question 表里已经没有 tags 列了。
     *
     * <p>标签的唯一权威数据是 question_tag 关联表，这里只是为了方便
     * {@code GET /api/question/get}（返回完整实体）把标签一并返回，
     * 由 Service 查询后填充。MyBatis-Plus 不会把它写进 SQL。
     */
    @TableField(exist = false)
    private List<String> tagList;
}
