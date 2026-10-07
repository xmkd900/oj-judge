package com.ojjudge.service.Impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ojjudge.common.ErrorCode;
import com.ojjudge.common.UserConstant;
import com.ojjudge.dto.UserLoginDTO;
import com.ojjudge.dto.UserRegisterDTO;
import com.ojjudge.dto.UserUpdateMyDTO;
import com.ojjudge.entity.User;
import com.ojjudge.exception.BusinessException;
import com.ojjudge.mapper.UserMapper;
import com.ojjudge.security.JwtTokenProvider;
import com.ojjudge.security.LoginResult;
import com.ojjudge.security.LoginUserDetails;
import com.ojjudge.security.RefreshTokenService;
import com.ojjudge.security.SecurityUtils;
import com.ojjudge.security.TokenPair;
import com.ojjudge.service.UserService;
import com.ojjudge.vo.UserLoginVO;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    // ------------------------------------------------------------------ 注册

    @Override
    public Long register(UserRegisterDTO dto) {
        String userAccount = dto.getUserAccount();
        String userPassword = dto.getUserPassword();
        String checkPassword = dto.getCheckPassword();

        if (userAccount == null || userAccount.trim().length() < UserConstant.ACCOUNT_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能少于 " + UserConstant.ACCOUNT_MIN_LENGTH + " 位");
        }
        if (userPassword == null || userPassword.length() < UserConstant.PASSWORD_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能少于 " + UserConstant.PASSWORD_MIN_LENGTH + " 位");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        Long count = this.lambdaQuery().eq(User::getUserAccount, userAccount).count();
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.USER_REGISTER_FAILED, "该账号已被注册");
        }

        User user = new User();
        user.setUserAccount(userAccount);
        // 明文密码只在这一行出现，之后库里只有 BCrypt 哈希
        user.setUserPassword(passwordEncoder.encode(userPassword));
        // 昵称默认取账号，否则新用户头像旁会显示"未命名"
        user.setUserName(userAccount);
        user.setUserRole(UserConstant.DEFAULT_ROLE);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        this.save(user);
        return user.getId();
    }

    // ------------------------------------------------------------------ 登录

    @Override
    public LoginResult login(UserLoginDTO dto) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(dto.getUserAccount(), dto.getUserPassword()));
            LoginUserDetails principal = (LoginUserDetails) authentication.getPrincipal();
            User user = this.getById(principal.getUserId());
            if (user == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
            }
            TokenPair tokenPair = issueTokenPair(user);
            return new LoginResult(tokenPair, toLoginVO(user));
        } catch (DisabledException e) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "账号已被封禁，请联系管理员");
        } catch (BadCredentialsException | UsernameNotFoundException e) {
            // 只有这两类才真的是"账号或密码不对"
            throw new BusinessException(ErrorCode.USER_LOGIN_FAILED, "用户账号或密码错误");
        } catch (AuthenticationException e) {
            // 其余认证异常（典型如查库失败被包装成 InternalAuthenticationServiceException）
            // 绝不能伪装成密码错误，否则线上永远查不出真正原因
            log.error("登录过程发生非预期异常，请检查数据库表结构与实体是否一致", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "登录失败，请联系管理员");
        }
    }

    // -------------------------------------------------------------- 刷新令牌

    @Override
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        Claims claims = jwtTokenProvider.parseQuietly(refreshToken);
        if (claims == null || !JwtTokenProvider.TYPE_REFRESH.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        Long userId = jwtTokenProvider.getUserId(claims);
        String oldJti = claims.getId();
        User user = this.getById(userId);
        if (user == null || UserConstant.ROLE_BAN.equals(user.getUserRole())) {
            refreshTokenService.revokeAll(userId);
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        // 轮换：旧 refresh token 用过一次就作废
        String newRefreshJti = jwtTokenProvider.newJti();
        boolean rotated = refreshTokenService.rotate(userId, oldJti, newRefreshJti,
                jwtTokenProvider.getRefreshExpirationSeconds());
        if (!rotated) {
            // 签名合法但 Redis 里没有记录 —— 令牌被重放，判定为泄露，踢掉该用户全部设备
            log.warn("检测到 refresh token 重放，userId={}，已吊销该用户全部刷新令牌", userId);
            refreshTokenService.revokeAll(userId);
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态已失效，请重新登录");
        }
        return issueTokenPair(user, newRefreshJti);
    }

    // ------------------------------------------------------------------ 登出

    @Override
    public void logout(String refreshToken) {
        // 1. 吊销 Cookie 里的 refresh token（单设备登出）
        if (refreshToken != null && !refreshToken.isBlank()) {
            Claims claims = jwtTokenProvider.parseQuietly(refreshToken);
            if (claims != null && JwtTokenProvider.TYPE_REFRESH.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))) {
                refreshTokenService.revoke(jwtTokenProvider.getUserId(claims), claims.getId());
            }
        }
        // 2. 把当前 access token 加入黑名单，弥补 JWT 无状态、登出后仍可使用的短板
        LoginUserDetails principal = SecurityUtils.currentUser();
        if (principal.getTokenJti() != null) {
            long remainingSeconds = (principal.getTokenExpiresAt() - System.currentTimeMillis()) / 1000;
            refreshTokenService.blacklistAccessToken(principal.getTokenJti(), remainingSeconds);
        }
    }

    // ------------------------------------------------------------- 更新个人信息

    @Override
    public boolean updateMy(UserUpdateMyDTO dto) {
        Long userId = SecurityUtils.currentUserId();
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }

        User update = new User();
        update.setId(userId);
        boolean dirty = false;
        if (dto.getUserName() != null) {
            update.setUserName(dto.getUserName());
            dirty = true;
        }
        if (dto.getUserAvatar() != null) {
            update.setUserAvatar(dto.getUserAvatar());
            dirty = true;
        }
        if (dto.getUserProfile() != null) {
            update.setUserProfile(dto.getUserProfile());
            dirty = true;
        }
        if (!dirty) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "没有需要更新的字段");
        }
        update.setUpdateTime(LocalDateTime.now());
        return this.updateById(update);
    }

    // ------------------------------------------------------- 获取当前登录用户

    @Override
    public UserLoginVO getCurrentUser() {
        Long userId = SecurityUtils.currentUserId();
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        return toLoginVO(user);
    }

    // ------------------------------------------------------------------ 工具

    private TokenPair issueTokenPair(User user) {
        return issueTokenPair(user, jwtTokenProvider.newJti());
    }

    private TokenPair issueTokenPair(User user, String refreshJti) {
        String accessJti = jwtTokenProvider.newJti();
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUserAccount(), user.getUserRole(), accessJti);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUserAccount(), user.getUserRole(), refreshJti);
        refreshTokenService.store(user.getId(), refreshJti, jwtTokenProvider.getRefreshExpirationSeconds());
        return new TokenPair(accessToken, refreshToken, jwtTokenProvider.getAccessExpirationSeconds());
    }

    private UserLoginVO toLoginVO(User user) {
        UserLoginVO vo = new UserLoginVO();
        vo.setId(user.getId());
        vo.setUserName(user.getUserName());
        vo.setUserAvatar(user.getUserAvatar());
        vo.setUserProfile(user.getUserProfile());
        vo.setUserRole(user.getUserRole());
        vo.setCreateTime(user.getCreateTime());
        vo.setUpdateTime(user.getUpdateTime());
        return vo;
    }
}
