package com.ojjudge.dto;

import com.ojjudge.common.SortablePageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 管理员查询用户列表。
 *
 * <p>注意它和 {@link UserUpdateMyDTO} 毫无关系：这是管理员查<strong>别人</strong>用的，
 * 可以带 userName / userRole 等筛选条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryDTO extends SortablePageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 昵称，模糊查询
     */
    private String userName;

    private String userProfile;

    /**
     * 角色精确过滤：user / admin / ban
     */
    private String userRole;
}
