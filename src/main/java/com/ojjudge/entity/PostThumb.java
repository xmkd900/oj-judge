package com.ojjudge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子点赞。
 *
 * <p>这张表的首要作用不是"记录谁点了赞"，而是靠
 * {@code uk_post_user(post_id, user_id)} 这个<b>唯一索引防止重复点赞</b>。
 *
 * <p>并发下两个人（或同一个人的两个请求）同时点赞时，
 * 应用层"先查有没有赞过再插入"是挡不住的 —— 两个请求都查到"没赞过"，
 * 然后都插入成功，点赞数就被多加了一次。
 * 只有数据库唯一索引能保证第二次插入必然失败。
 */
@Data
@TableName("post_thumb")
public class PostThumb implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @TableField("id")
    private Long id;

    @TableField("post_id")
    private Long postId;

    @TableField("user_id")
    private Long userId;

    @TableField("create_time")
    private LocalDateTime createTime;
}
