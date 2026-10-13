import request, { BaseResponse, unwrap } from "@/api/request";
import { clearAccessToken, setAccessToken } from "@/utils/token";

/**
 * 认证相关接口。
 *
 * 这里没有复用 generated/ 里的 UserControllerService，原因是那份代码是按旧后端
 * （Session + 网关 8101）生成的，登录响应结构已经对不上了。等后端稳定后重新生成即可。
 */

export type { BaseResponse };

export interface LoginUserVO {
  id?: number;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  /** user / admin / ban */
  userRole?: string;
  createTime?: string;
  updateTime?: string;
}

export interface LoginResponse {
  accessToken: string;
  /** 有效期（秒） */
  expiresIn: number;
  user: LoginUserVO;
}

export interface TokenResponse {
  accessToken: string;
  expiresIn: number;
}

export interface UserLoginRequest {
  userAccount: string;
  userPassword: string;
}

export interface UserRegisterRequest {
  userAccount: string;
  userPassword: string;
  checkPassword: string;
}

export interface UserUpdateMyRequest {
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
}

/**
 * 登录。成功后把 access token 存起来；
 * refresh token 由后端写进 HttpOnly Cookie，前端不用管。
 */
export async function login(payload: UserLoginRequest): Promise<LoginResponse> {
  const res = await request.post<BaseResponse<LoginResponse>>(
    "/api/user/login",
    payload
  );
  const data = unwrap(res.data);
  setAccessToken(data.accessToken, data.expiresIn);
  return data;
}

/** 注册 */
export async function register(
  payload: UserRegisterRequest
): Promise<number | undefined> {
  const res = await request.post<BaseResponse<number>>(
    "/api/user/register",
    payload
  );
  return unwrap(res.data);
}

/** 退出登录：后端吊销 refresh token + 拉黑 access token，前端清本地令牌 */
export async function logout(): Promise<void> {
  try {
    await request.post<BaseResponse<boolean>>("/api/user/logout");
  } finally {
    clearAccessToken();
  }
}

/** 获取当前登录用户（页面加载时恢复登录态） */
export async function getLoginUser(): Promise<LoginUserVO> {
  const res = await request.get<BaseResponse<LoginUserVO>>(
    "/api/user/get/login"
  );
  return unwrap(res.data);
}

/** 更新当前登录用户的个人信息 */
export async function updateMy(
  payload: UserUpdateMyRequest
): Promise<boolean | undefined> {
  const res = await request.post<BaseResponse<boolean>>(
    "/api/user/update/my",
    payload
  );
  return unwrap(res.data);
}
