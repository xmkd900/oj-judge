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
     * 账号最大长度。
     *
     * <p>之前只有下限没有上限，账号能一直填到撑满 varchar(256)。
     * 除了不美观，它还会让"删除用户时把账号改名腾位"这一步超长失败
     * （改名要追加 {@code #deleted#<id>} 后缀），见
     * {@code UserServiceImpl#doLogicalDelete}。
     */
    int ACCOUNT_MAX_LENGTH = 32;

    /**
     * 密码最小长度
     */
    int PASSWORD_MIN_LENGTH = 4;

    /**
     * 昵称最大长度
     */
    int USERNAME_MAX_LENGTH = 32;

    /**
     * 简介最大长度
     */
    int PROFILE_MAX_LENGTH = 512;
}
