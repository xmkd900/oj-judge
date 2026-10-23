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
 * 题目下的帖子（评论 / 讨论 / 题解）。
 *
 * <p>{@code thumb_num} 是<b>冗余计数</b>：它的权威来源是
 * {@code post_thumb} 表的行数，但每次列表都要 COUNT 就太重了，
 * 所以在这里存一份，靠 {@code PostServiceImpl} 的原子 UPDATE 维护。
 *
 * <p>正文是纯文本，不走 Markdown —— 省掉 XSS 过滤和渲染的复杂度。
 */
@Data
@TableName("post")
public class Post implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @TableField("id")
    private Long id;

    /**
     * 所属题目 id。帖子必须挂在某道题下面。
     */
    @TableField("question_id")
    private Long questionId;

    /**
     * 标题，可选（讨论帖可以没有，题解一般有）
     */
    @TableField("title")
    private String title;

    /**
     * 正文，纯文本
     */
    @TableField("content")
    private String content;

    /**
     * 点赞数（冗余，与 post_thumb 的行数对应）
     */
    @TableField("thumb_num")
    private Integer thumbNum;

    @TableField("user_id")
    private Long userId;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_delete")
    @TableLogic
    private Integer isDelete;
}
