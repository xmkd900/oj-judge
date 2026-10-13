package com.ojjudge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ojjudge.common.BaseResponse;
import com.ojjudge.dto.DeleteRequest;
import com.ojjudge.dto.UserAddDTO;
import com.ojjudge.dto.UserLoginDTO;
import com.ojjudge.dto.UserQueryDTO;
import com.ojjudge.dto.UserRegisterDTO;
import com.ojjudge.dto.UserUpdateDTO;
import com.ojjudge.dto.UserUpdateMyDTO;
import com.ojjudge.security.LoginResult;
import com.ojjudge.security.RefreshTokenCookieHelper;
import com.ojjudge.security.TokenPair;
import com.ojjudge.service.UserService;
import com.ojjudge.vo.LoginResponseVO;
import com.ojjudge.vo.TokenResponseVO;
import com.ojjudge.vo.UserLoginVO;
import com.ojjudge.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口。
 *
 * <p>鉴权约定：
 * <ul>
 *   <li>{@code /register}、{@code /login}、{@code /refresh} 公开</li>
 *   <li>其余接口需要 {@code Authorization: Bearer <access token>}</li>
 *   <li>Refresh Token 由 {@code /login}、{@code /refresh} 通过 HttpOnly Cookie 下发，前端不感知</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    private final RefreshTokenCookieHelper refreshTokenCookieHelper;

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public BaseResponse<Long> register(@RequestBody @Valid UserRegisterDTO userRegisterDTO) {
        return BaseResponse.success(userService.register(userRegisterDTO));
    }

    /**
     * 用户登录：返回 access token + 用户信息，refresh token 写入 HttpOnly Cookie
     */
    @PostMapping("/login")
    public BaseResponse<LoginResponseVO> login(@RequestBody @Valid UserLoginDTO userLoginDTO,
                                               HttpServletResponse response) {
        LoginResult result = userService.login(userLoginDTO);
        TokenPair tokenPair = result.getTokenPair();
        refreshTokenCookieHelper.write(response, tokenPair.getRefreshToken());
        return BaseResponse.success(new LoginResponseVO(
                tokenPair.getAccessToken(), tokenPair.getExpiresInSeconds(), result.getUser()));
    }

    /**
     * 刷新令牌：用 Cookie 中的 refresh token 换新的 access token，并轮换 refresh token
     */
    @PostMapping("/refresh")
    public BaseResponse<TokenResponseVO> refresh(HttpServletRequest request,
                                                 HttpServletResponse response) {
        String refreshToken = refreshTokenCookieHelper.read(request);
        TokenPair tokenPair = userService.refresh(refreshToken);
        refreshTokenCookieHelper.write(response, tokenPair.getRefreshToken());
        return BaseResponse.success(new TokenResponseVO(
                tokenPair.getAccessToken(), tokenPair.getExpiresInSeconds()));
    }

    /**
     * 退出登录：吊销 refresh token、拉黑 access token、清除 Cookie
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> logout(HttpServletRequest request,
                                        HttpServletResponse response) {
        userService.logout(refreshTokenCookieHelper.read(request));
        refreshTokenCookieHelper.clear(response);
        return BaseResponse.success(true);
    }

    /**
     * 更新当前登录用户的个人信息
     */
    @PostMapping("/update/my")
    public BaseResponse<Boolean> updateMy(@RequestBody @Valid UserUpdateMyDTO userUpdateMyDTO) {
        return BaseResponse.success(userService.updateMy(userUpdateMyDTO));
    }

    /**
     * 获取当前登录用户（页面加载时恢复登录态用）
     */
    @GetMapping("/get/login")
    public BaseResponse<UserLoginVO> getLoginUser() {
        return BaseResponse.success(userService.getCurrentUser());
    }

    // ============================================================== 管理员功能
    //
    // 权限用 @PreAuthorize 声明在方法上：声明跟着方法走，改 URL 不会让权限静默失效。
    // 角色常量是 ROLE_admin —— LoginUserDetails.getAuthorities() 加了 ROLE_ 前缀。

    /**
     * 分页查询用户列表（管理员）
     */
    @PostMapping("/list/page")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public BaseResponse<Page<UserVO>> listUserByPage(
            @RequestBody(required = false) UserQueryDTO userQueryDTO) {
        return BaseResponse.success(userService.listUserByPage(
                userQueryDTO == null ? new UserQueryDTO() : userQueryDTO));
    }

    /**
     * 按 id 查询用户（管理员）
     */
    @GetMapping("/get")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public BaseResponse<UserVO> getUserById(@RequestParam("id") Long id) {
        return BaseResponse.success(userService.getUserById(id));
    }

    /**
     * 创建用户（管理员）
     */
    @PostMapping("/add")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public BaseResponse<Long> addUser(@RequestBody @Valid UserAddDTO userAddDTO) {
        return BaseResponse.success(userService.addUser(userAddDTO));
    }

    /**
     * 修改用户（管理员）。可以改角色，但不能改自己的角色
     */
    @PostMapping("/update")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public BaseResponse<Boolean> updateUser(@RequestBody @Valid UserUpdateDTO userUpdateDTO) {
        return BaseResponse.success(userService.updateUser(userUpdateDTO));
    }

    /**
     * 删除用户（管理员）。推荐优先用封禁，真删会留下孤儿数据
     */
    @PostMapping("/delete")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public BaseResponse<Boolean> deleteUser(@RequestBody @Valid DeleteRequest deleteRequest) {
        return BaseResponse.success(userService.deleteUser(deleteRequest.getId()));
    }

    /**
     * 封禁用户（管理员）
     */
    @PostMapping("/ban")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public BaseResponse<Boolean> banUser(@RequestBody @Valid DeleteRequest deleteRequest) {
        return BaseResponse.success(userService.banUser(deleteRequest.getId()));
    }

    /**
     * 解除封禁（管理员）
     */
    @PostMapping("/unban")
    @PreAuthorize("hasAuthority('ROLE_admin')")
    public BaseResponse<Boolean> unbanUser(@RequestBody @Valid DeleteRequest deleteRequest) {
        return BaseResponse.success(userService.unbanUser(deleteRequest.getId()));
    }
}
