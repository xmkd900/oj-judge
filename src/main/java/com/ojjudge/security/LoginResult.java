package com.ojjudge.security;

import com.ojjudge.vo.UserLoginVO;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录结果：令牌对 + 用户信息
 */
@Data
@AllArgsConstructor
public class LoginResult {

    private TokenPair tokenPair;

    private UserLoginVO user;
}
