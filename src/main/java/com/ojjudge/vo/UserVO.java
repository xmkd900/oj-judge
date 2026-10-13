package com.ojjudge.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户脱敏视图（管理员视角）。
 *
 * <p>与 {@link UserLoginVO} 的区别：这个带 <strong>userAccount</strong>（账号），
 * 因为管理员要按账号找人；但<strong>绝不含密码</strong>。
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 登录账号，仅管理员可见
     */
    private String userAccount;

    private String userName;

    private String userAvatar;

    private String userProfile;

    /**
     * user / admin / ban
     */
    private String userRole;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
