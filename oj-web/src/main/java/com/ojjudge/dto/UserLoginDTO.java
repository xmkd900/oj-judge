package com.ojjudge.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserLoginDTO implements Serializable {

    // 用户账号
    @NotEmpty(message = "用户账号不能为空")
    private  String userAccount;

    // 用户密码
    @NotEmpty(message = "用户密码不能为空")
    private String userPassword;
}
