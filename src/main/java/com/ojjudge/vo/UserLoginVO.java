package com.ojjudge.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录用户脱敏视图（不含账号、密码）。
 *
 * <p>字段只保留前端真正消费的那些。
 * 曾经还有个 updateTime，但全前端搜不到任何使用点，已删除 ——
 * 将来要用再加回来即可：加字段永远比删字段容易。
 */
@Data
public class UserLoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 id
     */
    private Long id;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    /**
     * 用户角色：user/admin/ban
     */
    private String userRole;

    /**
     * 创建时间（个人中心展示"注册时间"）
     */
    private LocalDateTime createTime;
}
