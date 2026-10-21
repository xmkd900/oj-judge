package com.ojjudge.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ojjudge.entity.User;
import com.ojjudge.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 解析 Authorization: Bearer &lt;access token&gt;，把登录主体放进 SecurityContext。
 *
 * <p>不放行也不报错——解析失败就"当作匿名请求"继续走过滤器链，
 * 由 {@link com.ojjudge.config.SecurityConfig} 的授权规则和 AuthenticationEntryPoint 决定是否返回 401。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            Claims claims = jwtTokenProvider.parseQuietly(token);
            if (claims != null && JwtTokenProvider.TYPE_ACCESS.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))) {
                String jti = claims.getId();
                // 登出后的 access token 虽未过期也必须失效
                if (!refreshTokenService.isAccessTokenBlacklisted(jti)) {
                    resolvePrincipal(claims, jti, request);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 用 token 里的身份去数据库核对一遍，再决定放进 SecurityContext 的是什么。
     *
     * <h3>为什么不能只信 token</h3>
     * <p>token 里的 {@code role} 是<b>签发那一刻的快照</b>。
     * 用户拿着一张 role=user 的 token 登录着，管理员这时把他封禁了 ——
     * 数据库里已经是 ban，但他手里那张 token 还是 user，
     * 而且要到过期才失效。结果是：<b>封禁形同虚设，他照样能发帖、能提交</b>。
     *
     * <p>所以这里回查一次用户，用<b>当前</b>的角色构造身份。代价是每个带 token 的请求
     * 多一次主键查询（走了索引，且只取三列），换来的是权限状态永远是最新的。
     *
     * <p>用户被注销时 {@code selectById} 会返回 null（MyBatis-Plus 自动带
     * {@code is_delete = 0}），这时不设置认证 —— 等同于匿名请求。
     */
    private void resolvePrincipal(Claims claims, String jti, HttpServletRequest request) {
        Long userId = jwtTokenProvider.getUserId(claims);
        if (userId == null) {
            return;
        }

        User user;
        try {
            // 只取需要的三列，别把头像、简介那些大字段也拉出来
            user = userMapper.selectOne(new QueryWrapper<User>()
                    .select("id", "user_account", "user_role")
                    .eq("id", userId));
        } catch (Exception e) {
            // 这里刻意选"失败即拒绝"（fail-closed）而不是退回 token 里的角色：
            // 既然连当前状态都确认不了，就不能拿一张可能是过期的 token 放行，
            // 否则封禁又形同虚设了。代价是数据库抖动时该用户会被当成未登录。
            log.warn("核对用户状态失败，本次按未登录处理：userId={}", userId, e);
            return;
        }

        if (user == null) {
            // 用户已注销（selectOne 会自动带 is_delete=0）
            return;
        }

        // 角色以数据库当前值为准，不用 claims 里那份快照
        LoginUserDetails principal = new LoginUserDetails(
                userId, user.getUserAccount(), user.getUserRole(),
                jti, claims.getExpiration().getTime());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
