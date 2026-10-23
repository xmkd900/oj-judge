package com.ojjudge.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ojjudge.cache.CacheNames;
import com.ojjudge.cache.PostCacheService;
import com.ojjudge.cache.PostListItem;
import com.ojjudge.cache.PostThumbCounter;
import com.ojjudge.common.ErrorCode;
import com.ojjudge.dto.PostAddDTO;
import com.ojjudge.dto.PostQueryDTO;
import com.ojjudge.entity.Post;
import com.ojjudge.entity.PostThumb;
import com.ojjudge.entity.User;
import com.ojjudge.exception.BusinessException;
import com.ojjudge.mapper.PostMapper;
import com.ojjudge.mapper.PostThumbMapper;
import com.ojjudge.mapper.UserMapper;
import com.ojjudge.security.LoginUserDetails;
import com.ojjudge.security.SecurityUtils;
import com.ojjudge.service.PostService;
import com.ojjudge.service.QuestionService;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import com.ojjudge.vo.PostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 题目下的帖子。
 *
 * <h3>这里唯一有技术含量的地方是点赞</h3>
 * <p>见 {@link #thumbPost}。其余都是普通的增删改查。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostServiceImpl extends ServiceImpl<PostMapper, Post> implements PostService {

    /**
     * 正文长度上限。前端也会限制，这里是服务端兜底。
     */
    private static final int CONTENT_MAX_LENGTH = 5000;

    private final PostThumbMapper postThumbMapper;

    private final UserMapper userMapper;

    private final QuestionService questionService;

    private final PostCacheService postCacheService;

    private final PostThumbCounter postThumbCounter;

    private final CacheManager cacheManager;

    // ------------------------------------------------------------------ 发帖

    @Override
    public Long addPost(PostAddDTO dto) {
        LoginUserDetails current = SecurityUtils.currentUser();

        // 被封禁的账号不能发帖。isEnabled() 的定义就是"角色不是 ban"。
        if (!current.isEnabled()) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "账号已被封禁，无法发帖");
        }

        if (dto.getQuestionId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "缺少题目 id");
        }
        String content = dto.getContent();
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "内容不能为空");
        }
        if (content.length() > CONTENT_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "内容不能超过 " + CONTENT_MAX_LENGTH + " 字");
        }

        // 题目必须存在，否则帖子就成了孤儿
        questionService.getQuestionVoById(dto.getQuestionId());

        Post post = new Post();
        post.setQuestionId(dto.getQuestionId());
        post.setTitle(StringUtils.hasText(dto.getTitle()) ? dto.getTitle().trim() : null);
        post.setContent(content.trim());
        post.setThumbNum(0);
        post.setUserId(current.getUserId());
        post.setCreateTime(LocalDateTime.now());
        post.setUpdateTime(LocalDateTime.now());
        this.save(post);
        // 新帖要立刻出现在列表里，所以必须失效缓存。
        // 这一条和点赞不一样：点赞数晚几秒没关系，
        // 但自己发的帖刷不出来，用户会以为没发出去。
        evictPostList(dto.getQuestionId());
        return post.getId();
    }

    // ------------------------------------------------------------------ 删帖

    @Override
    public boolean deletePost(Long id) {
        LoginUserDetails current = SecurityUtils.currentUser();
        Post post = this.getByIdOrThrow(id);

        boolean owner = post.getUserId().equals(current.getUserId());
        if (!owner && !current.isAdmin()) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能删除自己发的帖子");
        }
        boolean removed = this.removeById(id);
        if (removed) {
            evictPostList(post.getQuestionId());
        }
        return removed;
    }

    // ------------------------------------------------------------------ 列表

    /**
     * 某道题下面的帖子，按时间倒序。公开接口，游客也能看。
     *
     * <h3>缓存怎么做的</h3>
     * <p>缓存的是<b>帖子本身</b>（正文、作者、点赞数），不含
     * {@code hasThumb} / {@code own} 这两个因人而异的字段 —— 那两个每次请求单独查。
     * 详见 {@link com.ojjudge.cache.CacheNames#POST_LIST}。
     *
     * <p>所以这里其实是三步：取缓存 → 内存切片（缓存的是整份列表）→ 补上个人状态。
     * 缓存命中时只剩"查我赞过哪些"这一条 SQL，未登录则一条都没有。
     */
    @Override
    public Page<PostVO> listPostByPage(PostQueryDTO dto) {
        Long currentUserId = SecurityUtils.currentUserIdOrNull();
        Long questionId = dto.getQuestionId();

        // 1. 整份帖子列表（走缓存；questionId 为 null 时不缓存，直接查库）
        List<PostListItem> all = postCacheService.loadPostList(questionId);

        // 2. 内存分页
        long total = all.size();
        int start = (int) Math.min(Math.max((dto.getCurrent() - 1) * dto.getPageSize(), 0), total);
        int end = (int) Math.min(start + dto.getPageSize(), total);
        List<PostListItem> pageItems = all.subList(start, end);

        // 3. 补上两个"每次都不一样"的部分，它们都不进列表缓存：
        //    点赞数（变化太频繁）、和我有关的状态（因人而异）
        final Set<Long> thumbed = loadThumbed(pageItems, currentUserId);
        Map<Long, Long> counts = postThumbCounter.getBatch(
                pageItems.stream().map(PostListItem::getId).toList());

        Page<PostVO> voPage = new Page<>(dto.getCurrent(), dto.getPageSize(), total);
        voPage.setRecords(pageItems.stream()
                .map(item -> toVO(item, thumbed, counts, currentUserId))
                .toList());
        return voPage;
    }

    /**
     * 查"我赞过这一页里的哪些"。
     *
     * <p>这是每次请求都要查的（不缓存）—— 它因人而异，
     * 缓存下来就是把一个人的点赞状态给别人看。
     *
     * <p>用 {@code final} + 分支赋值，lambda 里才能引用。
     */
    private Set<Long> loadThumbed(List<PostListItem> items, Long currentUserId) {
        if (currentUserId == null || items.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> postIds = items.stream().map(PostListItem::getId).toList();
        try {
            return postThumbMapper.selectList(new QueryWrapper<PostThumb>()
                            .eq("user_id", currentUserId)
                            .in("post_id", postIds))
                    .stream()
                    .map(PostThumb::getPostId)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            // 查不到就当都没赞过。列表本身还能正常显示，只是点赞按钮不高亮，
            // 不值得为这个让整个接口失败。
            log.warn("查询点赞状态失败：userId={}", currentUserId, e);
            return Collections.emptySet();
        }
    }

    /**
     * 失效某道题的帖子列表缓存。
     *
     * <p>这里手写清缓存而不是用 {@code @CacheEvict}，因为调用方手里只有帖子 id，
     * 题目 id 得先查出来；而且这几个地方都希望"缓存没清掉也不影响业务"。
     */
    private void evictPostList(Long questionId) {
        if (questionId == null) {
            return;
        }
        try {
            Cache cache = cacheManager.getCache(CacheNames.POST_LIST);
            if (cache != null) {
                cache.evict(questionId);
            }
        } catch (Exception e) {
            // 缓存清不掉也不能让业务失败 —— 最多是短暂显示旧数据，有 TTL 兜底
            log.warn("失效帖子列表缓存失败：questionId={}", questionId, e);
        }
    }

    // ------------------------------------------------------------------ 点赞

    /**
     * 点赞 / 取消点赞（切换）。
     *
     * <h3>为什么是"先删后插"，而不是"先查有没有赞过"</h3>
     * <p>最容易写错的顺序是：
     * <pre>
     *   if (没赞过) { 插入点赞记录; thumb_num + 1; }
     * </pre>
     * 并发下两个请求都查到"没赞过"，于是都插入、都 +1，点赞数就被多加了一次
     * （而且同一个人点了两次赞）。
     *
     * <p>这里的做法把判断交给数据库的<b>唯一索引</b>：
     * <ol>
     *   <li>先尝试删除点赞记录。删掉了 → 说明之前赞过 → 这是"取消点赞"</li>
     *   <li>没删掉 → 说明之前没赞过 → 插入。
     *       并发下只有一次插入能成功，另一个撞唯一索引失败</li>
     *   <li>只有插入成功的那个才会去 {@code thumb_num + 1}</li>
     * </ol>
     * 这样点赞数和"是否赞过"永远一致，不会多加。
     *
     * <p>计数用 {@code thumb_num = thumb_num + 1} 而不是"读出来加一再写回"，
     * 后者在并发下会丢更新。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean thumbPost(Long postId) {
        Long userId = SecurityUtils.currentUserId();
        PostThumb thumb = new PostThumb();
        thumb.setPostId(postId);
        thumb.setUserId(userId);
        thumb.setCreateTime(LocalDateTime.now());

        try {
            // INSERT IGNORE 让唯一索引来判断"之前赞过没有"：
            //   返回 1 = 之前没赞过，这次新增，计数才 +1
            //   返回 0 = 之前就赞过了，什么都不做
            // 所以无论并发多少请求，计数最多只加一次 —— 这就是幂等。
            if (postThumbMapper.insertIgnore(thumb) > 0) {
                // 只动 Redis 计数器，**不失效列表缓存** ——
                // 列表缓存里本来就不含点赞数，没必要重建。
                postThumbCounter.increase(postId);
            }
            return true;                      // 现在已点赞
        } catch (DuplicateKeyException | DeadlockLoserDataAccessException e) {
            // 并发撞车：状态其实是对的，回读一次如实返回，不抛错误给用户
            log.debug("点赞并发冲突，回读真实状态：postId={} userId={} {}",
                    postId, userId, e.getClass().getSimpleName());
            return hasThumbed(postId, userId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean unthumbPost(Long postId) {
        Long userId = SecurityUtils.currentUserId();
        Post post = this.getByIdOrThrow(postId);

        try {
            // 同样幂等：只有真的删掉了记录，计数才 -1。
            // 本来就没赞过（或并发下已被别人删掉）时，deleted = 0，计数不动。
            int deleted = postThumbMapper.delete(new QueryWrapper<PostThumb>()
                    .eq("post_id", postId)
                    .eq("user_id", userId));
            if (deleted > 0) {
                postThumbCounter.decrease(postId);
            }
            return false;                     // 现在未点赞
        } catch (DeadlockLoserDataAccessException e) {
            log.debug("取消点赞并发冲突，回读真实状态：postId={} userId={}", postId, userId);
            return hasThumbed(postId, userId);
        }
    }

    /**
     * 这个人现在到底有没有赞过这个帖子。
     *
     * <p>并发撞车时用：与其抛一个"操作失败"给用户，
     * 不如如实告诉他现在是什么状态 —— 反正状态本身是对的。
     */
    private boolean hasThumbed(Long postId, Long userId) {
        try {
            return postThumbMapper.countByPostAndUser(postId, userId) > 0;
        } catch (Exception e) {
            log.warn("回读点赞状态失败：postId={} userId={}", postId, userId, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ 工具

    private Post getByIdOrThrow(Long id) {
        Post post = this.getById(id);
        if (post == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "帖子不存在");
        }
        return post;
    }

    private void increaseThumbNum(Long postId) {
        this.lambdaUpdate()
                .eq(Post::getId, postId)
                .setSql("thumb_num = thumb_num + 1")
                .update();
    }

    private void decreaseThumbNum(Long postId) {
        // gt(0) 是为了兜底：万一计数和实际记录数对不上，也别减成负数
        this.lambdaUpdate()
                .eq(Post::getId, postId)
                .gt(Post::getThumbNum, 0)
                .setSql("thumb_num = thumb_num - 1")
                .update();
    }

    /**
    /**
     * 把缓存里的帖子条目补上"和我有关"的字段，变成对外返回的 VO。
     *
     * <p>作者昵称头像来自缓存（它们很少变，改了靠 TTL 兜底）；
     * {@code hasThumb} / {@code own} 在这里现算，不进缓存。
     */
    private PostVO toVO(PostListItem item, Set<Long> thumbed,
                        Map<Long, Long> counts, Long currentUserId) {
        PostVO vo = new PostVO();
        vo.setId(item.getId());
        vo.setQuestionId(item.getQuestionId());
        vo.setTitle(item.getTitle());
        vo.setContent(item.getContent());
        // 点赞数来自 Redis 计数器，不在列表缓存里
        vo.setThumbNum(counts.getOrDefault(item.getId(), 0L).intValue());
        vo.setUserId(item.getUserId());
        vo.setCreateTime(item.getCreateTime());
        vo.setUserName(item.getUserName());
        vo.setUserAvatar(item.getUserAvatar());

        boolean mine = currentUserId != null && currentUserId.equals(item.getUserId());
        vo.setOwn(mine);
        vo.setHasThumb(currentUserId != null && thumbed.contains(item.getId()));
        return vo;
    }
}
