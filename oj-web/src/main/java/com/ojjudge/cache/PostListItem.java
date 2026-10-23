package com.ojjudge.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 缓存里的一条帖子 —— <b>不含任何"因人而异"的字段</b>。
 *
 * <p>这是帖子缓存能否成立的关键。{@code PostVO} 里带着
 * {@code hasThumb}（我赞过没）和 {@code own}（是不是我发的），
 * 这两个字段随请求者变化，一旦进了缓存，
 * 就是<b>把 A 的点赞状态显示给 B</b> —— 和用户数据串号是同一类事故。
 *
 * <p>所以缓存用这个"人人看到都一样"的版本，
 * 那两个字段在每次请求时单独查、单独填。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostListItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long questionId;

    private String title;

    private String content;

    /**
     * ⚠️ 这里<b>刻意没有 thumbNum</b>。
     *
     * <p>点赞数是全场变化最频繁的字段，而正文、作者这些信息几乎不变。
     * 把它们塞进同一个缓存对象，结果就是：随便谁点个赞，
     * 整个列表缓存都得失效重建 —— 缓存基本等于白做。
     *
     * <p>点赞数改由 Redis 单独维护（见 {@code PostThumbCounter}），
     * 这次列表请求再按页取回来拼上。
     */
    private Long userId;

    private LocalDateTime createTime;

    // ---------------- 作者（只放展示用的昵称和头像，不暴露账号、角色）----------------

    private String userName;

    private String userAvatar;
}
