package com.ojjudge.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
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
        return this.removeById(id);
    }

    // ------------------------------------------------------------------ 列表

    /**
     * 某道题下面的帖子，按时间倒序。
     *
     * <p>⚠️ 这个接口<b>不走缓存</b>，原因有两条：
     * <ol>
     *   <li>点赞是高频写，缓存会被频繁击穿</li>
     *   <li>返回值里有 {@code hasThumb}（我赞过没）和 {@code own}（是不是我发的），
     *      这是<b>因请求者而异</b>的字段，缓存下来就是 A 看到 B 的点赞状态</li>
     * </ol>
     */
    @Override
    public Page<PostVO> listPostByPage(PostQueryDTO dto) {
        Long currentUserId = SecurityUtils.currentUserIdOrNull();

        Page<Post> page = new Page<>(dto.getCurrent(), dto.getPageSize());
        // 走 idx_question_ctime：过滤和排序都在索引里，不会 filesort
        QueryWrapper<Post> wrapper = new QueryWrapper<>();
        wrapper.eq(dto.getQuestionId() != null, "question_id", dto.getQuestionId())
                .orderByDesc("create_time");

        Page<Post> result = this.page(page, wrapper);
        List<Post> posts = result.getRecords();

        Page<PostVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(toVOList(posts, currentUserId));
        return voPage;
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
        this.getByIdOrThrow(postId);

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
                increaseThumbNum(postId);
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
        this.getByIdOrThrow(postId);

        try {
            // 同样幂等：只有真的删掉了记录，计数才 -1。
            // 本来就没赞过（或并发下已被别人删掉）时，deleted = 0，计数不动。
            int deleted = postThumbMapper.delete(new QueryWrapper<PostThumb>()
                    .eq("post_id", postId)
                    .eq("user_id", userId));
            if (deleted > 0) {
                decreaseThumbNum(postId);
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
     * 批量转 VO。作者信息和点赞状态都是<b>批量</b>查的，不能每条帖子查一次（N+1）。
     */
    private List<PostVO> toVOList(List<Post> posts, Long currentUserId) {
        if (posts == null || posts.isEmpty()) {
            return Collections.emptyList();
        }

        // 作者：一次查回所有发帖人
        List<Long> authorIds = posts.stream().map(Post::getUserId).distinct().toList();
        Map<Long, User> authors = new HashMap<>();
        for (User u : userMapper.selectBatchIds(authorIds)) {
            authors.put(u.getId(), u);
        }

        // 我赞过哪些：一次查回这一页里我点过赞的帖子。
        // 用 final + 分支赋值，lambda 里才能引用它。
        final Set<Long> thumbed;
        if (currentUserId != null) {
            List<Long> postIds = posts.stream().map(Post::getId).toList();
            thumbed = postThumbMapper.selectList(new QueryWrapper<PostThumb>()
                            .eq("user_id", currentUserId)
                            .in("post_id", postIds))
                    .stream()
                    .map(PostThumb::getPostId)
                    .collect(Collectors.toSet());
        } else {
            thumbed = Collections.emptySet();
        }

        return posts.stream()
                .map(post -> toVO(post, authors.get(post.getUserId()), thumbed, currentUserId))
                .toList();
    }

    private PostVO toVO(Post post, User author, Set<Long> thumbed, Long currentUserId) {
        PostVO vo = new PostVO();
        vo.setId(post.getId());
        vo.setQuestionId(post.getQuestionId());
        vo.setTitle(post.getTitle());
        vo.setContent(post.getContent());
        vo.setThumbNum(post.getThumbNum());
        vo.setUserId(post.getUserId());
        vo.setCreateTime(post.getCreateTime());

        // 只暴露昵称和头像，不把账号、角色这些带出去
        if (author != null) {
            vo.setUserName(author.getUserName());
            vo.setUserAvatar(author.getUserAvatar());
        }

        boolean mine = currentUserId != null && currentUserId.equals(post.getUserId());
        vo.setOwn(mine);
        vo.setHasThumb(currentUserId != null && thumbed.contains(post.getId()));
        return vo;
    }
}
