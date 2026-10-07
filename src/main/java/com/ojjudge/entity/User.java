package com.ojjudge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体。
 *
 * <p>字段名必须与数据库的列严格对应，否则 MyBatis-Plus 生成的 SQL 会查到不存在的列，
 * 登录时表现为 500 / 认证异常。修改本类前请先核对表结构。
 */
@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户昵称，对应列 user_name
     */

    private String userName;

    /**
     * 登录账号，对应列 user_account
     */
    @TableField("user_account")
    private String userAccount;

    @TableField("user_avatar")
    private String userAvatar;

    /**
     * BCrypt 密码哈希，对应列 user_password
     */
    @TableField("user_password")
    private String userPassword;

    @TableField("user_profile")
    private String userProfile;

    @TableField("user_role")
    private String userRole;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("is_delete")
    @TableLogic
    private Integer isDelete;

}
