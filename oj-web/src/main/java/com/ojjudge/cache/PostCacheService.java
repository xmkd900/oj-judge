package com.ojjudge.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ojjudge.entity.Post;
import com.ojjudge.entity.User;
import com.ojjudge.mapper.PostMapper;
import com.ojjudge.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 帖子列表的查询 + 缓存。
 *
 * <p>单独成一个 Bean，是因为 Spring 的缓存注解靠 AOP 代理生效，
 * <b>同一个类里的方法互相调用不走代理</b>。
 * 缓存方法必须放在别的 Bean 里，PostServiceImpl 才能"跨 Bean"调到它。
 * （同一个坑见 {@code QuestionCacheService}。）
 *
 * <h3>缓存的是什么</h3>
 * <p>某道题下的<b>全部</b>帖子，按时间倒序，含作者昵称头像。
 * 不含「我赞过没」「是不是我发的」—— 那些因人而异，每次请求单独查。
 *
 * <p>缓存整份而不是某一页，好处是翻到第几页都命中，
 * 且一个题目只有一个 key，失效时删这一个就够。
 * 分页在 {@code PostServiceImpl} 里做内存切片。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostCacheService {

    /**
     * 帖子太多就不缓存了。
     *
     * <p>缓存的是整份列表，一个题目的评论要是攒到几千条，
     * 每次请求都把一个几百 KB 的值从 Redis 拖回来再切片，反而比直接查库更亏。
     * 超过这个数量就老老实实查库 —— 反正那时候分页 SQL 本身也不慢。
     */
    private static final int MAX_CACHED_POSTS = 500;

    private final PostMapper postMapper;

    private final UserMapper userMapper;

    /**
     * 取某道题下的全部帖子（按时间倒序）。
     *
     * @param questionId 题目 id；为 null 表示"不分题目"，这种情况不缓存
     */
    /**
     * ⚠️ 这里<b>没有加 {@code sync = true}</b>，因为 Spring 不允许
     * {@code sync} 和 {@code unless} 同时出现
     * （会抛 {@code IllegalStateException: A sync=true operation does not support the unless attribute}）。
     *
     * <p>两者权衡后留下了 {@code unless}：
     * <ul>
     *   <li>{@code unless} 防止帖子攒太多时把超大列表塞进缓存 —— 这是实打实的风险</li>
     *   <li>{@code sync} 防击穿，但帖子列表就两条 SQL，
     *       缓存过期瞬间有几个并发打库也扛得住，代价小得多</li>
     * </ul>
     */
    @Cacheable(cacheNames = CacheNames.POST_LIST,
            key = "#questionId",
            condition = "#questionId != null",
            // 太多就不存，见 MAX_CACHED_POSTS
            unless = "#result == null || #result.size() > " + MAX_CACHED_POSTS)
    public List<PostListItem> loadPostList(Long questionId) {
        // 注意 eq 的第一个参数是"是否加这个条件"：
        // questionId 为 null 时不能写成 eq("question_id", null)，
        // 那会变成 question_id IS NULL，一条都查不到。
        List<Post> posts = postMapper.selectList(new QueryWrapper<Post>()
                .eq(questionId != null, "question_id", questionId)
                .orderByDesc("create_time"));
        return toItems(posts);
    }

    private List<PostListItem> toItems(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return Collections.emptyList();
        }

        // 作者批量查一次，不要每条帖子查一次（N+1）
        List<Long> authorIds = posts.stream().map(Post::getUserId).distinct().toList();
        Map<Long, User> authors = new HashMap<>();
        for (User u : userMapper.selectBatchIds(authorIds)) {
            authors.put(u.getId(), u);
        }

        return posts.stream().map(post -> {
            PostListItem item = new PostListItem();
            item.setId(post.getId());
            item.setQuestionId(post.getQuestionId());
            item.setTitle(post.getTitle());
            item.setContent(post.getContent());
            // 注意这里不 setThumbNum：点赞数不走列表缓存，见 PostListItem 的说明
            item.setUserId(post.getUserId());
            item.setCreateTime(post.getCreateTime());
            User author = authors.get(post.getUserId());
            if (author != null) {
                item.setUserName(author.getUserName());
                item.setUserAvatar(author.getUserAvatar());
            }
            return item;
        }).toList();
    }
}
