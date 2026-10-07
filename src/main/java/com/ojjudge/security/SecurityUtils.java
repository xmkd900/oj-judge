package com.ojjudge.security;

import com.ojjudge.common.ErrorCode;
import com.ojjudge.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 获取当前登录用户的工具方法
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 取当前登录主体，未登录抛 40100
     */
    public static LoginUserDetails currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUserDetails principal)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return principal;
    }

    /**
     * 取当前登录用户 id，未登录抛 40100
     */
    public static Long currentUserId() {
        return currentUser().getUserId();
    }
}
