package com.ojjudge.config;

import com.ojjudge.security.AccessDeniedHandlerImpl;
import com.ojjudge.security.AuthenticationEntryPointImpl;
import com.ojjudge.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置：完全无状态，由 JWT 驱动。
 *
 * <p>放行规则集中在这里，新增公开接口记得同步维护。
 */
@Configuration
@RequiredArgsConstructor
/**
 * 开启方法级安全，允许在 Controller 方法上写 {@code @PreAuthorize("hasAuthority('ROLE_admin')")}。
 *
 * <p>这样做而不是在 SecurityConfig 里堆 URL 的原因：权限声明跟着<strong>方法</strong>走，
 * 而方法比 URL 稳定得多 —— 改了 URL 不会让权限静默失效。
 */
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 无需登录即可访问的接口
     */
    private static final String[] PUBLIC_POST_URLS = {
            "/api/user/register",
            "/api/user/login",
            "/api/user/refresh",
            // 游客也能浏览题目列表
            "/api/question/list/page/vo"
    };

    /**
     * 无需登录的 GET 接口：题目详情（脱敏）、标签列表
     */
    private static final String[] PUBLIC_GET_URLS = {
            "/api/question/get/vo",
            "/api/question/tags"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthenticationEntryPointImpl authenticationEntryPoint;
    private final AccessDeniedHandlerImpl accessDeniedHandler;
    private final UserDetailsService userDetailsService;

    @Value("${cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 显式组装 AuthenticationManager：DaoAuthenticationProvider + BCrypt + 我们自己的 UserDetailsService。
     * 不用 AuthenticationConfiguration 是为了避免"自动配置到底用了哪个 Provider"的不确定性。
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(provider);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Refresh Token 走 Cookie，前端必须 withCredentials
        config.setAllowCredentials(true);
        config.setExposedHeaders(List.of("Authorization"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 无状态，不用 Cookie 里的 JSESSIONID，也不防 CSRF（令牌不放 Cookie 就不怕 CSRF）
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_URLS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_URLS).permitAll()
                        // 预检请求直接放行，否则带 Authorization 头的跨域请求会被拦
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated());

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
