import axios, {
  AxiosError,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from "axios";
import {
  clearAccessToken,
  getAccessToken,
  isAccessTokenExpired,
  setAccessToken,
} from "@/utils/token";

/**
 * 后端地址。
 * 后端端口在 application.yml 的 server.port 里配的是 8121（8080 被前端 dev server 占了）。
 */
export const BASE_URL = "http://localhost:8121";

const request = axios.create({
  baseURL: BASE_URL,
  timeout: 30000,
  // 关键：refresh token 在 HttpOnly Cookie 里，必须允许跨域携带凭证
  withCredentials: true,
});

/**
 * 会话彻底失效时的回调（由路由层注册）。
 * 这里不直接 import router，避免 request → router → view → request 的循环依赖。
 */
let onSessionExpired: (() => void) | null = null;

export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler;
}

/** 这些接口本身不需要带令牌，401 也不能拿它们去刷新 */
const NO_AUTH_URLS = [
  "/api/user/login",
  "/api/user/register",
  "/api/user/refresh",
];

function isNoAuthUrl(url = ""): boolean {
  return NO_AUTH_URLS.some((item) => url.includes(item));
}

// ---------------------------------------------------------------- 请求拦截

request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    if (!isNoAuthUrl(config.url)) {
      const token = getAccessToken();
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// ---------------------------------------------------------------- 刷新令牌

/**
 * 并发单飞：同一时刻多个请求都收到 401，只真正刷新一次，其余排队复用结果。
 */
let refreshing: Promise<string> | null = null;

async function doRefresh(): Promise<string> {
  // 用裸 axios，避免走本实例的拦截器造成递归
  const res = await axios.post(`${BASE_URL}/api/user/refresh`, null, {
    withCredentials: true,
    timeout: 10000,
  });
  const body = res.data;
  if (body?.code !== 0 || !body?.data?.accessToken) {
    throw new Error(body?.message ?? "刷新令牌失败");
  }
  setAccessToken(body.data.accessToken, body.data.expiresIn);
  return body.data.accessToken as string;
}

async function refreshAccessToken(): Promise<string> {
  if (!refreshing) {
    refreshing = doRefresh().finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

// ---------------------------------------------------------------- 响应拦截

/** 给重试请求打标记，防止无限重试 */
interface RetryableConfig extends InternalAxiosRequestConfig {
  _retry?: boolean;
}

function isUnauthorized(error: AxiosError): boolean {
  const status = error.response?.status;
  if (status === 401) {
    return true;
  }
  // 兜底：某些代理会把 401 吞成 200，此时看业务码
  const body = error.response?.data as { code?: number } | undefined;
  return body?.code === 40100 || body?.code === 40110 || body?.code === 40111;
}

request.interceptors.response.use(
  (response: AxiosResponse) => response,
  async (error: AxiosError) => {
    const originalConfig = error.config as RetryableConfig | undefined;

    // 只有"需要鉴权的接口"才值得去刷新；登录/注册本身的 401（账号密码错）不能刷新
    if (
      !originalConfig ||
      originalConfig._retry ||
      !isUnauthorized(error) ||
      isNoAuthUrl(originalConfig.url)
    ) {
      return Promise.reject(error);
    }

    originalConfig._retry = true;
    try {
      // 本地令牌已经过期，或压根没有令牌，都先尝试用 Cookie 里的 refresh token 续期
      const newToken = await refreshAccessToken();
      originalConfig.headers.Authorization = `Bearer ${newToken}`;
      return request(originalConfig);
    } catch {
      // 刷新令牌也失效了 —— 会话结束，清理现场并交给路由层跳登录页
      clearAccessToken();
      onSessionExpired?.();
      return Promise.reject(error);
    }
  }
);

export { isAccessTokenExpired };
export default request;
