package com.ojjudge.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ojjudge.common.CommonConstant;
import com.ojjudge.common.ErrorCode;
import com.ojjudge.common.SortUtils;
import com.ojjudge.common.UserConstant;
import com.ojjudge.dto.UserAddDTO;
import com.ojjudge.dto.UserLoginDTO;
import com.ojjudge.dto.UserQueryDTO;
import com.ojjudge.dto.UserRegisterDTO;
import com.ojjudge.dto.UserUpdateDTO;
import com.ojjudge.dto.UserUpdateMyDTO;
import com.ojjudge.entity.User;
import com.ojjudge.exception.BusinessException;
import com.ojjudge.mapper.UserMapper;
import com.ojjudge.security.JwtTokenProvider;
import com.ojjudge.security.LoginResult;
import com.ojjudge.security.LoginUserDetails;
import com.ojjudge.security.RefreshTokenService;
import com.ojjudge.security.SecurityUtils;
import com.ojjudge.security.TokenPair;
import com.ojjudge.service.UserService;
import com.ojjudge.vo.UserLoginVO;
import com.ojjudge.vo.UserVO;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 用户被删除后，账号改名用的标记。后面会拼上 id，保证每次删除都不重复。
     * 详见 {@link #doLogicalDelete}。
     */
    private static final String DELETED_ACCOUNT_SUFFIX = "#deleted#";

    /**
     * {@code user.user_account} 的列宽（varchar(256)），用于改名时兜底截断。
     */
    private static final int ACCOUNT_COLUMN_LENGTH = 256;


    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    // ------------------------------------------------------------------ 注册

    @Override
    public Long register(UserRegisterDTO dto) {
        String userAccount = dto.getUserAccount();
        String userPassword = dto.getUserPassword();
        String checkPassword = dto.getCheckPassword();

        if (userAccount == null || userAccount.trim().length() < UserConstant.ACCOUNT_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能少于 " + UserConstant.ACCOUNT_MIN_LENGTH + " 位");
        }
        if (userAccount.length() > UserConstant.ACCOUNT_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能超过 " + UserConstant.ACCOUNT_MAX_LENGTH + " 位");
        }
        if (userPassword == null || userPassword.length() < UserConstant.PASSWORD_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能少于 " + UserConstant.PASSWORD_MIN_LENGTH + " 位");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        Long count = this.lambdaQuery().eq(User::getUserAccount, userAccount).count();
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.USER_REGISTER_FAILED, "该账号已被注册");
        }

        User user = new User();
        user.setUserAccount(userAccount);
        // 明文密码只在这一行出现，之后库里只有 BCrypt 哈希
        user.setUserPassword(passwordEncoder.encode(userPassword));
        // 昵称默认取账号，否则新用户头像旁会显示"未命名"
        user.setUserName(userAccount);
        user.setUserRole(UserConstant.DEFAULT_ROLE);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        this.save(user);
        return user.getId();
    }

    // ------------------------------------------------------------------ 登录

    @Override
    public LoginResult login(UserLoginDTO dto) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(dto.getUserAccount(), dto.getUserPassword()));
            LoginUserDetails principal = (LoginUserDetails) authentication.getPrincipal();
            User user = this.getById(principal.getUserId());
            if (user == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
            }
            TokenPair tokenPair = issueTokenPair(user);
            return new LoginResult(tokenPair, toLoginVO(user));
        } catch (DisabledException e) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "账号已被封禁，请联系管理员");
        } catch (BadCredentialsException | UsernameNotFoundException e) {
            // 只有这两类才真的是"账号或密码不对"
            throw new BusinessException(ErrorCode.USER_LOGIN_FAILED, "用户账号或密码错误");
        } catch (AuthenticationException e) {
            // 其余认证异常（典型如查库失败被包装成 InternalAuthenticationServiceException）
            // 绝不能伪装成密码错误，否则线上永远查不出真正原因
            log.error("登录过程发生非预期异常，请检查数据库表结构与实体是否一致", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "登录失败，请联系管理员");
        }
    }

    // -------------------------------------------------------------- 刷新令牌

    @Override
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        Claims claims = jwtTokenProvider.parseQuietly(refreshToken);
        if (claims == null || !JwtTokenProvider.TYPE_REFRESH.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        Long userId = jwtTokenProvider.getUserId(claims);
        String oldJti = claims.getId();
        User user = this.getById(userId);
        if (user == null || UserConstant.ROLE_BAN.equals(user.getUserRole())) {
            refreshTokenService.revokeAll(userId);
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        // 轮换：旧 refresh token 用过一次就作废
        String newRefreshJti = jwtTokenProvider.newJti();
        boolean rotated = refreshTokenService.rotate(userId, oldJti, newRefreshJti,
                jwtTokenProvider.getRefreshExpirationSeconds());
        if (!rotated) {
            // 签名合法但 Redis 里没有记录 —— 令牌被重放，判定为泄露，踢掉该用户全部设备
            log.warn("检测到 refresh token 重放，userId={}，已吊销该用户全部刷新令牌", userId);
            refreshTokenService.revokeAll(userId);
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "登录状态已失效，请重新登录");
        }
        return issueTokenPair(user, newRefreshJti);
    }

    // ------------------------------------------------------------------ 登出

    @Override
    public void logout(String refreshToken) {
        // 1. 吊销 Cookie 里的 refresh token（单设备登出）
        if (refreshToken != null && !refreshToken.isBlank()) {
            Claims claims = jwtTokenProvider.parseQuietly(refreshToken);
            if (claims != null && JwtTokenProvider.TYPE_REFRESH.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))) {
                refreshTokenService.revoke(jwtTokenProvider.getUserId(claims), claims.getId());
            }
        }
        // 2. 把当前 access token 加入黑名单，弥补 JWT 无状态、登出后仍可使用的短板
        LoginUserDetails principal = SecurityUtils.currentUser();
        if (principal.getTokenJti() != null) {
            long remainingSeconds = (principal.getTokenExpiresAt() - System.currentTimeMillis()) / 1000;
            refreshTokenService.blacklistAccessToken(principal.getTokenJti(), remainingSeconds);
        }
    }

    // ------------------------------------------------------------- 更新个人信息

    @Override
    public boolean updateMy(UserUpdateMyDTO dto) {
        Long userId = SecurityUtils.currentUserId();
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }

        User update = new User();
        update.setId(userId);
        boolean dirty = false;
        if (dto.getUserName() != null) {
            update.setUserName(dto.getUserName());
            dirty = true;
        }
        if (dto.getUserAvatar() != null) {
            update.setUserAvatar(dto.getUserAvatar());
            dirty = true;
        }
        if (dto.getUserProfile() != null) {
            update.setUserProfile(dto.getUserProfile());
            dirty = true;
        }
        if (!dirty) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "没有需要更新的字段");
        }
        update.setUpdateTime(LocalDateTime.now());
        return this.updateById(update);
    }

    // ------------------------------------------------------- 获取当前登录用户

    @Override
    public UserLoginVO getCurrentUser() {
        Long userId = SecurityUtils.currentUserId();
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        return toLoginVO(user);
    }

    // ------------------------------------------------------------------ 工具

    private TokenPair issueTokenPair(User user) {
        return issueTokenPair(user, jwtTokenProvider.newJti());
    }

    private TokenPair issueTokenPair(User user, String refreshJti) {
        String accessJti = jwtTokenProvider.newJti();
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUserAccount(), user.getUserRole(), accessJti);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getUserAccount(), user.getUserRole(), refreshJti);
        refreshTokenService.store(user.getId(), refreshJti, jwtTokenProvider.getRefreshExpirationSeconds());
        return new TokenPair(accessToken, refreshToken, jwtTokenProvider.getAccessExpirationSeconds());
    }

    private UserLoginVO toLoginVO(User user) {
        UserLoginVO vo = new UserLoginVO();
        vo.setId(user.getId());
        vo.setUserName(user.getUserName());
        vo.setUserAvatar(user.getUserAvatar());
        vo.setUserProfile(user.getUserProfile());
        vo.setUserRole(user.getUserRole());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

    private UserVO toUserVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUserAccount(user.getUserAccount());
        vo.setUserName(user.getUserName());
        vo.setUserAvatar(user.getUserAvatar());
        vo.setUserProfile(user.getUserProfile());
        vo.setUserRole(user.getUserRole());
        vo.setCreateTime(user.getCreateTime());
        vo.setUpdateTime(user.getUpdateTime());
        return vo;
    }

    // ==================================================================== 管理员功能
    //
    // 入口权限由 Controller 上的 @PreAuthorize 保证，这里只负责业务规则与三条红线。

    @Override
    public Page<UserVO> listUserByPage(UserQueryDTO userQueryDTO) {
        Page<User> page = new Page<>(userQueryDTO.getCurrent(), userQueryDTO.getPageSize());
        SortUtils.apply(page, userQueryDTO.getSortField(), userQueryDTO.getSortOrder(),
                CommonConstant.USER_SORT_FIELDS, "createTime");

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(userQueryDTO.getId() != null, User::getId, userQueryDTO.getId())
                .like(StringUtils.hasText(userQueryDTO.getUserName()), User::getUserName, userQueryDTO.getUserName())
                .eq(StringUtils.hasText(userQueryDTO.getUserRole()), User::getUserRole, userQueryDTO.getUserRole());

        Page<User> result = this.page(page, wrapper);
        Page<UserVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toUserVO).toList());
        return voPage;
    }

    @Override
    public UserVO getUserById(Long id) {
        User user = this.getById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        return toUserVO(user);
    }

    @Override
    public Long addUser(UserAddDTO userAddDTO) {
        String account = userAddDTO.getUserAccount().trim();
        Long count = this.lambdaQuery().eq(User::getUserAccount, account).count();
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该账号已被注册");
        }

        String role = normalizeRole(userAddDTO.getUserRole(), UserConstant.DEFAULT_ROLE);

        User user = new User();
        user.setUserAccount(account);
        // 初始密码由服务端生成，不是管理员指定的
        user.setUserPassword(passwordEncoder.encode(randomInitPassword()));
        user.setUserName(StringUtils.hasText(userAddDTO.getUserName())
                ? userAddDTO.getUserName() : account);
        user.setUserAvatar(userAddDTO.getUserAvatar());
        user.setUserProfile(userAddDTO.getUserProfile());
        user.setUserRole(role);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        this.save(user);
        return user.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateUser(UserUpdateDTO userUpdateDTO) {
        Long targetId = userUpdateDTO.getId();
        User user = this.getById(targetId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }

        Long currentId = SecurityUtils.currentUserId();

        // 红线一：不能改自己的角色。否则手滑把自己降成普通用户，后台就再也进不去了
        if (userUpdateDTO.getUserRole() != null && targetId.equals(currentId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "不能修改自己的角色");
        }

        // 红线二：角色值白名单，客户端传什么都不能照单全收
        String newRole = normalizeRole(userUpdateDTO.getUserRole(), user.getUserRole());
        boolean roleChanged = userUpdateDTO.getUserRole() != null;

        if (roleChanged) {
            // 红线三：不能把最后一个管理员降权
            if (!UserConstant.ROLE_ADMIN.equals(newRole)
                    && UserConstant.ROLE_ADMIN.equals(user.getUserRole())
                    && countAdmin() <= 1) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "至少要保留一个管理员");
            }
        }

        // 部分更新：与 updateMy / 题目编辑统一约定 —— null = 不修改
        boolean dirty = false;
        if (userUpdateDTO.getUserName() != null) {
            user.setUserName(userUpdateDTO.getUserName());
            dirty = true;
        }
        if (userUpdateDTO.getUserAvatar() != null) {
            user.setUserAvatar(userUpdateDTO.getUserAvatar());
            dirty = true;
        }
        if (userUpdateDTO.getUserProfile() != null) {
            user.setUserProfile(userUpdateDTO.getUserProfile());
            dirty = true;
        }
        if (roleChanged) {
            user.setUserRole(newRole);
            dirty = true;
        }
        if (!dirty) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "没有需要更新的字段");
        }

        user.setUpdateTime(LocalDateTime.now());
        return this.updateById(user);
    }

    @Override
    public boolean deleteUser(Long id) {
        User user = this.getById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        if (id.equals(SecurityUtils.currentUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "不能删除自己");
        }
        if (UserConstant.ROLE_ADMIN.equals(user.getUserRole()) && countAdmin() <= 1) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "至少要保留一个管理员");
        }
        return doLogicalDelete(user);
    }

    /**
     * 逻辑删除用户，并先把账号腾出来。
     *
     * <p>⚠️ 为什么要多"改名"这一步：逻辑删除<b>只是打个标记，行还留在表里</b>。
     * 而 {@code user_account} 上有唯一索引（防止并发注册撞号），
     * 那么这条"已删除"的记录会一直占着这个名字 —— 结果就是这个账号
     * <b>永远无法再被注册</b>，连原来的主人都找不回来。
     *
     * <p>所以删除前先把账号改成一个绝对不会冲突的值（原名 + 标记 + id），
     * 把名字释放出来。已删除的行反正查不到（MyBatis-Plus 会自动带
     * {@code is_delete = 0}），账号被改名没有任何副作用。
     *
     * <p>后缀里带 {@code id} 是关键：只加固定后缀的话，同名账号删两次
     * 依然会撞唯一索引，而 id 保证了每次删除都得到不同的值。
     */
    private boolean doLogicalDelete(User user) {
        String renamed = user.getUserAccount() + DELETED_ACCOUNT_SUFFIX + user.getId();
        // 数据库是 varchar(256)。虽然注册时限制了账号长度，但对历史数据不放心，
        // 这里兜个底：真超长就砍掉开头、保留尾部 —— 尾部的 id 是保证唯一性的关键，
        // 绝不能砍。
        if (renamed.length() > ACCOUNT_COLUMN_LENGTH) {
            renamed = renamed.substring(renamed.length() - ACCOUNT_COLUMN_LENGTH);
        }

        User rename = new User();
        rename.setId(user.getId());
        rename.setUserAccount(renamed);
        rename.setUpdateTime(LocalDateTime.now());
        this.updateById(rename);

        return this.removeById(user.getId());
    }

    @Override
    public boolean banUser(Long id) {
        User user = this.getById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        if (id.equals(SecurityUtils.currentUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "不能封禁自己");
        }
        if (UserConstant.ROLE_ADMIN.equals(user.getUserRole()) && countAdmin() <= 1) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "至少要保留一个管理员");
        }
        user.setUserRole(UserConstant.ROLE_BAN);
        user.setUpdateTime(LocalDateTime.now());
        return this.updateById(user);
    }

    @Override
    public boolean unbanUser(Long id) {
        User user = this.getById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        if (!UserConstant.ROLE_BAN.equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该用户未被封禁");
        }
        user.setUserRole(UserConstant.ROLE_USER);
        user.setUpdateTime(LocalDateTime.now());
        return this.updateById(user);
    }

    // ------------------------------------------------------------ 管理员工具

    /**
     * 角色白名单校验：只允许 user / admin / ban，其余一律退回默认值。
     *
     * <p>别小看这一步 —— 不校验的话客户端传个 "superadmin" 进来，
     * 而我们所有权限判断都是 {@code equals("admin")}，那这个账号反而会变成<strong>零权限</strong>，
     * 或者将来加了别的判断逻辑时出现非预期行为。
     */
    private String normalizeRole(String role, String fallback) {
        if (!StringUtils.hasText(role)) {
            return fallback;
        }
        String trimmed = role.trim();
        if (UserConstant.ROLE_USER.equals(trimmed)
                || UserConstant.ROLE_ADMIN.equals(trimmed)
                || UserConstant.ROLE_BAN.equals(trimmed)) {
            return trimmed;
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "角色只能是 user / admin / ban");
    }

    private long countAdmin() {
        Long count = this.lambdaQuery().eq(User::getUserRole, UserConstant.ROLE_ADMIN).count();
        return count == null ? 0 : count;
    }

    /**
     * 管理员建号时的随机初始密码。
     *
     * <p>真实项目这里应该把密码通过安全渠道告知用户，并强制首次登录修改。
     * 当前只是生成一个足够随机的串，避免管理员设一个"他和用户都知道"的弱密码。
     */
    private String randomInitPassword() {
        return "Init@" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
