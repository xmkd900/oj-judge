package com.ojjudge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 题目标签关联。
 *
 * <p>为什么不用 question.tags 那个 JSON 字符串列：
 * <ul>
 *   <li>JSON 列上的 JSON_CONTAINS 实测<b>走不了索引</b>（多值索引建得上但优化器不用），
 *       5000 行就全表扫描；关联表的 tag 列有普通索引。</li>
 *   <li>JSON 列做不了 GROUP BY 统计，标签云 / 热门标签无从谈起。</li>
 * </ul>
 *
 * <p>question.tags 那一列仍然会写（冗余），但筛选与展示一律以本表为准。
 */
@Data
@TableName("question_tag")
public class QuestionTag implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @TableField("id")
    private Long id;

    @TableField("question_id")
    private Long questionId;

    @TableField("tag")
    private String tag;

    @TableField("create_time")
    private LocalDateTime createTime;
}
