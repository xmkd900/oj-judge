package com.ojjudge.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ojjudge.entity.User;
import com.ojjudge.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 供 DaoAuthenticationProvider 在登录时加载用户并校验 BCrypt 密码
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String userAccount) throws UsernameNotFoundException {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("user_account", userAccount));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return new LoginUserDetails(user.getId(), user.getUserAccount(), user.getUserRole(), user.getUserPassword());
    }
}
