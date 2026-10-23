package com.ojjudge.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 更新当前登录用户的个人信息。
 * 字段全部可选，为 null 表示不修改；传空字符串表示清空。
 */
@Data
public class UserUpdateMyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 32, message = "昵称长度不能超过 32")
    private String userName;

    @Size(max = 512, message = "头像地址长度不能超过 512")
    private String userAvatar;

    @Size(max = 512, message = "个人简介长度不能超过 512")
    private String userProfile;
}
