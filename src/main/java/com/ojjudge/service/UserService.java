package com.ojjudge.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.ojjudge.dto.UserLoginDTO;
import com.ojjudge.dto.UserRegisterDTO;
import com.ojjudge.dto.UserUpdateMyDTO;
import com.ojjudge.entity.User;
import com.ojjudge.security.LoginResult;
import com.ojjudge.security.TokenPair;
import com.ojjudge.vo.UserLoginVO;

public interface UserService extends IService<User> {

    /**
     * 注册
     *
     * @return 新用户 id
     */
    Long register(UserRegisterDTO userRegisterDTO);

    /**
     * 登录：校验密码 → 签发令牌对 → Redis 登记 refresh token
     */
    LoginResult login(UserLoginDTO userLoginDTO);

    /**
     * 用 refresh token 换一对新令牌（旧 refresh token 立即作废）
     */
    TokenPair refresh(String refreshToken);

    /**
     * 登出：吊销 refresh token，并把当前 access token 加入黑名单
     *
     * @param refreshToken Cookie 中的 refresh token，可能为 null
     */
    void logout(String refreshToken);

    /**
     * 更新当前登录用户的个人信息
     */
    boolean updateMy(UserUpdateMyDTO userUpdateMyDTO);

    /**
     * 获取当前登录用户的脱敏信息
     */
    UserLoginVO getCurrentUser();
}
