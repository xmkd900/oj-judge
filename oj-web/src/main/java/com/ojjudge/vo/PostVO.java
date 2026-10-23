package com.ojjudge.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 帖子视图。
 *
 * <p>⚠️ {@code hasThumb} 和 {@code own} 是<b>因请求者而异</b>的字段
 * （"我有没有赞过"、"是不是我发的"）。
 * 带这种字段的对象<b>绝对不能进缓存</b> ——
 * 否则会把一个人的点赞状态显示给另一个人。
 *
 * <p>所以帖子缓存用的是 {@code PostListItem}（不含这两个字段的版本），
 * 本 VO 只是每次请求时在 {@code PostServiceImpl} 里临时拼出来的。
 */
@Data
public class PostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long questionId;

    /**
     * 标题，可能为空
     */
    private String title;

    /**
     * 正文，纯文本
     */
    private String content;

    /**
     * 点赞数
     */
    private Integer thumbNum;

    private Long userId;

    private LocalDateTime createTime;

    // ---------------- 作者信息（不暴露账号，只给昵称和头像）----------------

    private String userName;

    private String userAvatar;

    // ---------------- 与当前登录用户相关 ----------------

    /**
     * 我是否点赞过。未登录恒为 false。
     */
    private Boolean hasThumb;

    /**
     * 是不是我发的。前端据此决定是否显示删除按钮。
     */
    private Boolean own;
}
