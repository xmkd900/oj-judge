package com.ojjudge.security;

import com.ojjudge.common.UserConstant;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security 的登录主体。
 *
 * <p>三种构造来源：
 * <ul>
 *   <li>登录时由 {@link UserDetailsServiceImpl} 从数据库加载，带密码哈希，交给 DaoAuthenticationProvider 校验</li>
 *   <li>后续请求由 {@link JwtAuthenticationFilter} 从 JWT 声明构造，不带密码，但带上当前令牌的 jti 与过期时间（登出时要用）</li>
 * </ul>
 */
@Getter
public class LoginUserDetails implements UserDetails {

    private final Long userId;
    private final String account;
    private final String role;
    private final String password;

    /**
     * 当前 Access Token 的 jti，用于登出时加入黑名单
     */
    private final String tokenJti;

    /**
     * 当前 Access Token 的过期时间戳（毫秒）
     */
    private final long tokenExpiresAt;

    public LoginUserDetails(Long userId, String account, String role) {
        this(userId, account, role, "", null, 0L);
    }

    /**
     * 数据库加载
     */
    public LoginUserDetails(Long userId, String account, String role, String password) {
        this(userId, account, role, password, null, 0L);
    }

    /**
     * JWT 声明构造
     */
    public LoginUserDetails(Long userId, String account, String role, String tokenJti, long tokenExpiresAt) {
        this(userId, account, role, "", tokenJti, tokenExpiresAt);
    }

    private LoginUserDetails(Long userId, String account, String role, String password,
                             String tokenJti, long tokenExpiresAt) {
        this.userId = userId;
        this.account = account;
        this.role = role == null ? UserConstant.DEFAULT_ROLE : role;
        this.password = password;
        this.tokenJti = tokenJti;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return account;
    }

    /**
     * 封禁用户判定为不可用：登录被拒，携带旧令牌访问也会被拒
     */
    @Override
    public boolean isEnabled() {
        return !UserConstant.ROLE_BAN.equals(role);
    }
}
