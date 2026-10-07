package com.ojjudge.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;

@Data
public class UserRegisterDTO implements Serializable {
    @NotEmpty(message = "用户账号不能为空")
    private String userAccount;
    @NotEmpty(message = "用户密码不能为空")
    private String userPassword;
    @NotEmpty(message = "用户确认密码不能为空")
    private String checkPassword;

}
