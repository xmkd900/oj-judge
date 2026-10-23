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

    /**
     * 取当前登录用户 id，<b>未登录返回 null</b>。
     *
     * <p>用于公开接口里"顺便"带上和当前用户相关的状态 ——
     * 比如帖子列表是游客也能看的，但登录的人想知道"我赞过没"。
     * 这种场景不能因为没登录就整个接口失败，所以用这个而不是
     * {@link #currentUserId()}。
     */
    public static Long currentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LoginUserDetails principal)) {
            return null;
        }
        return principal.getUserId();
    }
}
