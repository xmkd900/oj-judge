package com.ojjudge.common;

/**
 * 用户相关常量
 */
public interface UserConstant {

    /**
     * 默认角色
     */
    String DEFAULT_ROLE = "user";

    String ROLE_ADMIN = "admin";

    String ROLE_USER = "user";

    String ROLE_BAN = "ban";

    /**
     * 账号最小长度
     */
    int ACCOUNT_MIN_LENGTH = 4;

    /**
     * 密码最小长度
     */
    int PASSWORD_MIN_LENGTH = 6;

    /**
     * 昵称最大长度
     */
    int USERNAME_MAX_LENGTH = 32;

    /**
     * 简介最大长度
     */
    int PROFILE_MAX_LENGTH = 512;
}
