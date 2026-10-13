import request, { BaseResponse, unwrap } from "@/api/request";

/**
 * 管理员接口。
 *
 * 全部需要 admin 角色，后端用 @PreAuthorize 守卫；
 * 前端这里只负责发请求，权限判断交给路由守卫和后端。
 */

export interface UserVO {
  id?: number;
  /** 登录账号，仅管理员可见 */
  userAccount?: string;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  /** user / admin / ban */
  userRole?: string;
  createTime?: string;
  updateTime?: string;
}

export interface UserQueryRequest {
  current?: number;
  pageSize?: number;
  sortField?: string;
  sortOrder?: string;
  id?: number;
  userName?: string;
  userRole?: string;
}

export interface UserAddRequest {
  userAccount: string;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
}

export interface UserUpdateRequest {
  id: number;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
}

export interface PageResult<T> {
  records: T[];
  total: number;
}

/** 分页查询用户列表 */
export async function listUserByPage(
  params: UserQueryRequest
): Promise<PageResult<UserVO>> {
  const res = await request.post<BaseResponse<PageResult<UserVO>>>(
    "/api/user/list/page",
    params
  );
  return unwrap(res.data);
}

/** 按 id 查询用户 */
export async function getUserById(id: number): Promise<UserVO> {
  const res = await request.get<BaseResponse<UserVO>>("/api/user/get", {
    params: { id },
  });
  return unwrap(res.data);
}

/** 创建用户。初始密码由服务端生成 */
export async function addUser(
  payload: UserAddRequest
): Promise<number | undefined> {
  const res = await request.post<BaseResponse<number>>(
    "/api/user/add",
    payload
  );
  return unwrap(res.data);
}

/** 修改用户（可改角色，但不能改自己的角色） */
export async function updateUser(
  payload: UserUpdateRequest
): Promise<boolean | undefined> {
  const res = await request.post<BaseResponse<boolean>>(
    "/api/user/update",
    payload
  );
  return unwrap(res.data);
}

/** 删除用户。推荐优先用封禁 */
export async function deleteUser(id: number): Promise<boolean | undefined> {
  const res = await request.post<BaseResponse<boolean>>("/api/user/delete", {
    id,
  });
  return unwrap(res.data);
}

/** 封禁用户 */
export async function banUser(id: number): Promise<boolean | undefined> {
  const res = await request.post<BaseResponse<boolean>>("/api/user/ban", {
    id,
  });
  return unwrap(res.data);
}

/** 解除封禁 */
export async function unbanUser(id: number): Promise<boolean | undefined> {
  const res = await request.post<BaseResponse<boolean>>("/api/user/unban", {
    id,
  });
  return unwrap(res.data);
}
