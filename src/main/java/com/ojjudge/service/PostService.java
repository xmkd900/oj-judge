package com.ojjudge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ojjudge.dto.PostAddDTO;
import com.ojjudge.dto.PostQueryDTO;
import com.ojjudge.vo.PostVO;

/**
 * 题目下的帖子（评论 / 讨论）。
 */
public interface PostService {

    /**
     * 发帖。需要登录，被封禁的账号不能发。
     *
     * @return 帖子 id
     */
    Long addPost(PostAddDTO dto);

    /**
     * 删帖。本人或管理员。
     */
    boolean deletePost(Long id);

    /**
     * 某道题下面的帖子列表，按时间倒序。公开接口，游客也能看。
     */
    Page<PostVO> listPostByPage(PostQueryDTO dto);

    /**
     * 点赞。<b>幂等</b>：已经赞过就什么都不做，结果仍然是"已点赞"。
     *
     * <p>为什么不做一个"点赞/取消"切换接口：切换不是幂等操作，
     * 并发下会互相抵消 —— 用户手抖点两下，第一次赞、第二次取消，回到原点。
     * 拆成两个幂等接口，重复点击（或并发重试）都不会出问题。
     */
    boolean thumbPost(Long postId);

    /**
     * 取消点赞。<b>幂等</b>：本来就没赞过就什么都不做。
     */
    boolean unthumbPost(Long postId);
}
