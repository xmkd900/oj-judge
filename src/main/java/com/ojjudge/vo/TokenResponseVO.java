package com.ojjudge.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 刷新令牌后的响应：只回新的 Access Token
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accessToken;

    private Long expiresIn;
}
