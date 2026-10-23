package com.ojjudge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员修改用户。
 *
 * <p>它和 {@link UserUpdateMyDTO} 的区别：
 * 这个<strong>有 id 和 userRole</strong>，因为是管理员改别人。
 * 两个类绝不合并 —— 合并后普通用户就能在 update/my 里给自己提权。
 */
@Data
public class UserUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "用户 id 不能为空")
    private Long id;

    private String userName;

    private String userAvatar;

    private String userProfile;

    /**
     * 角色，只能是 user / admin / ban 之一。
     * 改自己的角色会被拒绝，防止把自己降权后没人能进后台。
     */
    private String userRole;
}
