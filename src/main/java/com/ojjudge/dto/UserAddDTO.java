package com.ojjudge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员创建用户。
 *
 * <p>刻意<strong>不含密码字段</strong>：管理员建号时应当由系统生成随机初始密码，
 * 而不是让管理员设一个他知道、用户却不知道的密码。
 */
@Data
public class UserAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "账号不能为空")
    private String userAccount;

    private String userName;

    private String userAvatar;

    private String userProfile;

    /**
     * 角色，只能是 user / admin / ban 之一（服务端会校验）
     */
    private String userRole;
}
