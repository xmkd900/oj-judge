package com.ojjudge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ojjudge.common.BaseResponse;
import com.ojjudge.dto.DeleteRequest;
import com.ojjudge.dto.PostAddDTO;
import com.ojjudge.dto.PostQueryDTO;
import com.ojjudge.service.PostService;
import com.ojjudge.vo.PostVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题目下的帖子（评论 / 讨论）接口。
 *
 * <p>权限分工：
 * <ul>
 *   <li>公开（游客可看）：{@code list}。返回时会"顺便"带上和当前用户相关的
 *       {@code hasThumb} / {@code own}，没登录就都是 false。</li>
 *   <li>需登录：{@code add}、{@code thumb}（由 SecurityConfig 的
 *       {@code anyRequest().authenticated()} 保证）</li>
 *   <li>本人或管理员：{@code delete}（判断在 Service 里）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/post")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /**
     * 发帖
     */
    @PostMapping("/add")
    public BaseResponse<Long> addPost(@RequestBody @Validated PostAddDTO dto) {
        return BaseResponse.success(postService.addPost(dto));
    }

    /**
     * 删帖。本人或管理员。
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deletePost(@RequestBody @Valid DeleteRequest deleteRequest) {
        return BaseResponse.success(postService.deletePost(deleteRequest.getId()));
    }

    /**
     * 某道题下面的帖子列表，按时间倒序。公开接口。
     */
    @GetMapping("/list")
    public BaseResponse<Page<PostVO>> listPostByPage(PostQueryDTO dto) {
        return BaseResponse.success(postService.listPostByPage(dto));
    }

    /**
     * 点赞。幂等 —— 重复调用（或并发重试）不会让点赞数多加。
     */
    @PostMapping("/thumb")
    public BaseResponse<Boolean> thumbPost(@RequestParam("postId") Long postId) {
        return BaseResponse.success(postService.thumbPost(postId));
    }

    /**
     * 取消点赞。同样幂等。
     */
    @PostMapping("/unthumb")
    public BaseResponse<Boolean> unthumbPost(@RequestParam("postId") Long postId) {
        return BaseResponse.success(postService.unthumbPost(postId));
    }
}
