package com.ojjudge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 题目提交记录。
 */
@Data
@TableName("question_submit")
public class QuestionSubmit implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @TableField("id")
    private Long id;

    /**
     * 编程语言：java / cpp / go
     */
    @TableField("language")
    private String language;

    /**
     * 用户提交的源代码
     */
    @TableField("code")
    private String code;

    /**
     * 判题结果，数据库存 JSON 字符串
     */
    @TableField("judge_info")
    private String judgeInfo;

    /**
     * 状态：0 等待中 / 1 判题中 / 2 成功 / 3 失败
     */
    @TableField("status")
    private Integer status;

    @TableField("question_id")
    private Long questionId;

    @TableField("user_id")
    private Long userId;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_delete")
    @TableLogic
    private Integer isDelete;

    // ---------------------------------------------------------------- 状态常量

    public static final int STATUS_WAITING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCEED = 2;
    public static final int STATUS_FAILED = 3;
}
