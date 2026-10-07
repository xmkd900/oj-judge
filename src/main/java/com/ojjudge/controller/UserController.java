package com.ojjudge.controller;

import com.ojjudge.common.BaseResponse;
import com.ojjudge.dto.UserLoginDTO;
import com.ojjudge.dto.UserRegisterDTO;
import com.ojjudge.dto.UserUpdateMyDTO;
import com.ojjudge.security.LoginResult;
import com.ojjudge.security.RefreshTokenCookieHelper;
import com.ojjudge.security.TokenPair;
import com.ojjudge.service.UserService;
import com.ojjudge.vo.LoginResponseVO;
import com.ojjudge.vo.TokenResponseVO;
import com.ojjudge.vo.UserLoginVO;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
}
