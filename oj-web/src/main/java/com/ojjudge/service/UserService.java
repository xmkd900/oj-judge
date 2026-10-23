package com.ojjudge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ojjudge.dto.UserAddDTO;
import com.ojjudge.dto.UserLoginDTO;
import com.ojjudge.dto.UserQueryDTO;
import com.ojjudge.dto.UserRegisterDTO;
import com.ojjudge.dto.UserUpdateDTO;
import com.ojjudge.dto.UserUpdateMyDTO;
import com.ojjudge.entity.User;
import com.ojjudge.security.LoginResult;
import com.ojjudge.security.TokenPair;
import com.ojjudge.vo.UserLoginVO;
import com.ojjudge.vo.UserVO;

public interface UserService extends IService<User> {

    /**
     * 注册
     *
     * @return 新用户 id
     */
    Long register(UserRegisterDTO userRegisterDTO);

    /**
     * 登录：校验密码 → 签发令牌对 → Redis 登记 refresh token
     */
    LoginResult login(UserLoginDTO userLoginDTO);

    /**
     * 用 refresh token 换一对新令牌（旧 refresh token 立即作废）
     */
    TokenPair refresh(String refreshToken);

    /**
     * 登出：吊销 refresh token，并把当前 access token 加入黑名单
     *
     * @param refreshToken Cookie 中的 refresh token，可能为 null
     */
    void logout(String refreshToken);

    /**
     * 更新当前登录用户的个人信息
     */
    boolean updateMy(UserUpdateMyDTO userUpdateMyDTO);

    /**
     * 获取当前登录用户的脱敏信息
     */
    UserLoginVO getCurrentUser();

    // ------------------------------------------------------------ 管理员功能

    /**
     * 分页查询用户列表（管理员）。返回脱敏 VO，不含密码
     */
    Page<UserVO> listUserByPage(UserQueryDTO userQueryDTO);

    /**
     * 按 id 查询用户（管理员）。返回脱敏 VO，不含密码
     */
    UserVO getUserById(Long id);

    /**
     * 创建用户（管理员）。初始密码由服务端生成
     *
     * @return 新用户 id
     */
    Long addUser(UserAddDTO userAddDTO);

    /**
     * 修改用户（管理员）。可以改角色，但不能改自己的角色
     */
    boolean updateUser(UserUpdateDTO userUpdateDTO);

    /**
     * 删除用户（管理员）。
     *
     * <p>真实项目里很少真删 —— 他发的题、提交记录全是关联数据。
     * 这里保留接口，但推荐用 {@link #banUser(Long)} 代替。
     */
    boolean deleteUser(Long id);

    /**
     * 封禁用户：角色置为 ban，登录会被拒
     */
    boolean banUser(Long id);

    /**
     * 解除封禁：角色恢复为 user
     */
    boolean unbanUser(Long id);
}
